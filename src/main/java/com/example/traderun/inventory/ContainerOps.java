package com.example.traderun.inventory;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Safe, throttled client-side container operations (Paper-safe pacing).
 *
 * INPUT / OUTPUT SELECTION RULE:
 * - The "selected item" is defined ONLY by the top-left slot of the container UI.
 * - If that top-left slot is empty, we keep using the already-known remembered item,
 *   and we do NOT switch to other items just because they exist in the container.
 */
public final class ContainerOps {

    private ContainerOps() {}

    public enum Mode {
        /** Withdraw ONLY the selected input item until reserved empty slots remain. */
        WITHDRAW_INPUT_FILL_LEAVE_EMPTY,

        /** Deposit ONLY the selected output item into the container. */
        DEPOSIT_OUTPUT_ITEM,
        
        /** Deposit input items back to the input chest (for floor switching). */
        DEPOSIT_INPUT_ITEM,

        /** Legacy: deposit emerald blocks then emeralds. */
        DEPOSIT_EMERALDS
    }

    public static final class Session {
        public final Mode mode;
        /** Keep this many empty main slots free (withdraw mode). */
        public final int reservedEmptySlots;
        /** If >0, try to reach at least this many items in inventory (withdraw mode). */
        public final int desiredMinCount;

        public boolean done = false;
        public String error = null;

        /** Selected input item id (preseed from remembered per-floor). */
        public Identifier inputItemId = null;

        /** Selected output item id (preseed from remembered per-floor). */
        public Identifier outputItemId = null;

        private long lastClickMs = 0L;
        private long lastProgressMs = 0L;
        private final Random rng = new Random();

        public Session(Mode mode, int reservedEmptySlots, int desiredMinCount) {
            this.mode = mode;
            this.reservedEmptySlots = Math.max(0, reservedEmptySlots);
            this.desiredMinCount = desiredMinCount;
        }

        public int tickCount = 0;
        
        public void tick(MinecraftClient client) {
            if (done) return;
            if (client == null || client.player == null) return;
            
            tickCount++;

            // If no screen is open, wait - don't error out immediately
            if (client.currentScreen == null) {
                // Only error if we've been waiting too long with no screen
                if (tickCount > 60) { // ~3 seconds at 20 tps
                    error = "Screen closed unexpectedly (tick " + tickCount + ")";
                    done = true;
                }
                return; // Just wait
            }
            
            if (!(client.currentScreen instanceof HandledScreen<?>)) {
                // Some other screen type - wait a bit, it might transition
                if (tickCount > 40) {
                    error = "Not a container screen: " + client.currentScreen.getClass().getName() + " (tick " + tickCount + ")";
                    done = true;
                }
                return;
            }

            ScreenHandler handler = client.player.currentScreenHandler;
            if (handler == null) {
                error = "No screen handler";
                done = true;
                return;
            }

            List<Integer> containerSlots = getContainerSlotIndices(client.player, handler);
            if (containerSlots.isEmpty()) {
                error = "No container slots detected (handler has " + handler.slots.size() + " slots)";
                done = true;
                return;
            }

            switch (mode) {
                case WITHDRAW_INPUT_FILL_LEAVE_EMPTY -> tickWithdrawInput(client, handler, containerSlots);
                case DEPOSIT_OUTPUT_ITEM -> tickDepositOutputItem(client, handler, containerSlots);
                case DEPOSIT_INPUT_ITEM -> tickDepositInputItem(client, handler, containerSlots);
                case DEPOSIT_EMERALDS -> tickDepositEmeralds(client, handler);
            }
        }

        private void tickWithdrawInput(MinecraftClient client, ScreenHandler handler, List<Integer> containerSlots) {
            PlayerEntity player = client.player;

            Integer topLeftIdx = topLeftContainerSlotIndex(handler, containerSlots);
            if (topLeftIdx == null) {
                error = "No container slots (found " + containerSlots.size() + " slots)";
                done = true;
                return;
            }

            // Check first slot of INPUT container - this defines what INPUT item is
            Slot topLeft = handler.slots.get(topLeftIdx);
            ItemStack topLeftStack = topLeft.getStack();
            if (topLeftStack != null && !topLeftStack.isEmpty()) {
                Identifier id = Registries.ITEM.getId(topLeftStack.getItem());
                if (id == null) {
                    error = "Failed to identify input item";
                    done = true;
                    return;
                }
                // First slot has an item - this IS the input item (update if different)
                if (inputItemId == null || !inputItemId.equals(id)) {
                    inputItemId = id;
                }
            }
            // If first slot is empty, keep using remembered inputItemId (don't clear it)

            // We MUST know the input item to withdraw anything
            if (inputItemId == null) {
                // Check if container has any items at all
                int nonEmpty = 0;
                for (int idx : containerSlots) {
                    ItemStack st = handler.slots.get(idx).getStack();
                    if (st != null && !st.isEmpty()) nonEmpty++;
                }
                if (nonEmpty == 0) {
                    error = "INPUT container is empty - waiting for items";
                } else {
                    error = "Put input item in INPUT chest's first slot once to learn it";
                }
                done = true;
                return;
            }

            Item inputItem = Registries.ITEM.get(inputItemId);
            if (inputItem == null) {
                error = "Unknown input item id";
                done = true;
                return;
            }

            int emptyMain = emptyMainSlots(player);

            if (emptyMain <= reservedEmptySlots) {
                if (desiredMinCount > 0) {
                    int have = countInMain(player, inputItem);
                    if (have < desiredMinCount) {
                        int missing = desiredMinCount - have;
                        error = "inventory full, remove " + missing + " items";
                    }
                }
                done = true;
                return;
            }

            // Withdraw ONLY the selected input item - prioritize larger stacks
            int slotToMove = -1;
            int largestCount = 0;
            for (int idx : containerSlots) {
                Slot slot = handler.slots.get(idx);
                ItemStack st = slot.getStack();
                if (st == null || st.isEmpty()) continue;
                if (st.getItem() == inputItem) {
                    int count = st.getCount();
                    if (count > largestCount) {
                        largestCount = count;
                        slotToMove = idx;
                    }
                }
            }

            if (slotToMove == -1) {
                done = true;
                return;
            }

            if (!canClickNow()) return;

            int beforeCount = countInMain(player, inputItem);
            int beforeEmpty = emptyMainSlots(player);

            quickMove(client, handler, slotToMove);

            int afterCount = countInMain(player, inputItem);
            int afterEmpty = emptyMainSlots(player);

            boolean progress = (afterCount > beforeCount) || (afterEmpty < beforeEmpty);
            if (progress) {
                lastProgressMs = System.currentTimeMillis();
                return;
            }

            long now = System.currentTimeMillis();
            if (lastProgressMs == 0L) lastProgressMs = now;
            if (now - lastProgressMs > 1200L) {
                if (desiredMinCount > 0) {
                    int have = countInMain(player, inputItem);
                    if (have < desiredMinCount) {
                        int missing = desiredMinCount - have;
                        error = "inventory full, remove " + missing + " items";
                    }
                }
                done = true;
            }
        }

        private int lastDepositCount = -1;
        private long lastDepositProgressMs = 0L;
        
        private void tickDepositOutputItem(MinecraftClient client, ScreenHandler handler, List<Integer> containerSlots) {
            PlayerEntity player = client.player;

            Integer topLeftIdx = topLeftContainerSlotIndex(handler, containerSlots);
            if (topLeftIdx == null) {
                error = "No container slots detected";
                done = true;
                return;
            }

            // Check first slot of OUTPUT container - this defines what OUTPUT item is
            Slot topLeft = handler.slots.get(topLeftIdx);
            ItemStack topLeftStack = topLeft.getStack();
            if (topLeftStack != null && !topLeftStack.isEmpty()) {
                Identifier id = Registries.ITEM.getId(topLeftStack.getItem());
                if (id != null) {
                    // First slot has an item - this IS the output item (update if different)
                    if (outputItemId == null || !outputItemId.equals(id)) {
                        outputItemId = id;
                    }
                }
            }
            // If first slot is empty, keep using remembered outputItemId (don't clear it)

            // We MUST know the output item to deposit anything
            if (outputItemId == null) {
                error = "Output item unknown: put the output item in OUTPUT chest's first slot once";
                done = true;
                return;
            }

            Item outItem = Registries.ITEM.get(outputItemId);
            if (outItem == null) {
                error = "Unknown output item id: " + outputItemId;
                done = true;
                return;
            }

            // Count output items in player inventory
            int currentCount = countInMain(player, outItem);
            
            // Track progress
            if (lastDepositCount == -1) {
                lastDepositCount = currentCount;
                lastDepositProgressMs = System.currentTimeMillis();
            } else if (currentCount != lastDepositCount) {
                lastDepositCount = currentCount;
                lastDepositProgressMs = System.currentTimeMillis();
            }
            
            // If no progress for 3 seconds, container might be full - done
            if (System.currentTimeMillis() - lastDepositProgressMs > 3000L && currentCount > 0) {
                // Container might be full, done for now
                done = true;
                return;
            }

            // Deposit ONLY the output item - nothing else
            int outSlot = findPlayerInvSlot(handler, player, outItem);
            if (outSlot != -1) {
                if (!canClickNow()) return;
                quickMove(client, handler, outSlot);
                return;
            }

            // No more output items to deposit
            done = true;
        }
        
        private int lastInputDepositCount = -1;
        private long lastInputDepositProgressMs = 0L;
        
        private void tickDepositInputItem(MinecraftClient client, ScreenHandler handler, List<Integer> containerSlots) {
            PlayerEntity player = client.player;

            Integer topLeftIdx = topLeftContainerSlotIndex(handler, containerSlots);
            if (topLeftIdx == null) {
                error = "No container slots detected";
                done = true;
                return;
            }

            // We MUST know the input item to deposit
            if (inputItemId == null) {
                error = "Input item unknown - cannot return items";
                done = true;
                return;
            }

            Item inputItem = Registries.ITEM.get(inputItemId);
            if (inputItem == null) {
                error = "Unknown input item id: " + inputItemId;
                done = true;
                return;
            }

            // Count input items in player inventory
            int currentCount = countInMain(player, inputItem);
            
            // Track progress
            if (lastInputDepositCount == -1) {
                lastInputDepositCount = currentCount;
                lastInputDepositProgressMs = System.currentTimeMillis();
            } else if (currentCount != lastInputDepositCount) {
                lastInputDepositCount = currentCount;
                lastInputDepositProgressMs = System.currentTimeMillis();
            }
            
            // If no progress for 3 seconds, container might be full - done
            if (System.currentTimeMillis() - lastInputDepositProgressMs > 3000L && currentCount > 0) {
                // Container might be full, done for now
                done = true;
                return;
            }

            // Deposit ONLY the input item - nothing else
            int inputSlot = findPlayerInvSlot(handler, player, inputItem);
            if (inputSlot != -1) {
                if (!canClickNow()) return;
                quickMove(client, handler, inputSlot);
                return;
            }

            // No more input items to deposit
            done = true;
        }

        private void tickDepositEmeralds(MinecraftClient client, ScreenHandler handler) {
            PlayerEntity player = client.player;

            int emeraldBlockSlot = findPlayerInvSlot(handler, player, Items.EMERALD_BLOCK);
            if (emeraldBlockSlot != -1) {
                if (!canClickNow()) return;
                quickMove(client, handler, emeraldBlockSlot);
                return;
            }

            int emeraldSlot = findPlayerInvSlot(handler, player, Items.EMERALD);
            if (emeraldSlot != -1) {
                if (!canClickNow()) return;
                quickMove(client, handler, emeraldSlot);
                return;
            }

            done = true;
        }

        private boolean canClickNow() {
            long now = System.currentTimeMillis();
            long base = 220L;
            long jitter = rng.nextInt(41);
            long delay = base + jitter;
            if (now - lastClickMs < delay) return false;
            lastClickMs = now;
            return true;
        }

        private void quickMove(MinecraftClient client, ScreenHandler handler, int slotIndex) {
            try {
                client.interactionManager.clickSlot(
                        handler.syncId,
                        slotIndex,
                        0,
                        SlotActionType.QUICK_MOVE,
                        client.player
                );
            } catch (Throwable t) {
                error = "clickSlot failed";
                done = true;
            }
        }

        private static Integer topLeftContainerSlotIndex(ScreenHandler handler, List<Integer> containerSlots) {
            return containerSlots.stream()
                    .min(Comparator.<Integer>comparingInt(i -> handler.slots.get(i).y)
                            .thenComparingInt(i -> handler.slots.get(i).x))
                    .orElse(null);
        }
    }

    private static List<Integer> getContainerSlotIndices(PlayerEntity player, ScreenHandler handler) {
        List<Integer> out = new ArrayList<>();
        var playerInv = player.getInventory();

        for (int i = 0; i < handler.slots.size(); i++) {
            Slot s = handler.slots.get(i);
            if (s == null) continue;
            if (s.inventory == playerInv) continue;
            out.add(i);
        }
        return out;
    }

    private static int findPlayerInvSlot(ScreenHandler handler, PlayerEntity player, Item item) {
        var playerInv = player.getInventory();
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot s = handler.slots.get(i);
            if (s == null) continue;
            if (s.inventory != playerInv) continue;

            ItemStack st = s.getStack();
            if (st == null || st.isEmpty()) continue;
            if (st.getItem() == item) return i;
        }
        return -1;
    }

    private static int emptyMainSlots(PlayerEntity player) {
        int empty = 0;
        var main = player.getInventory().main;
        for (ItemStack st : main) {
            if (st == null || st.isEmpty()) empty++;
        }
        return empty;
    }

    private static int countInMain(PlayerEntity player, Item item) {
        int c = 0;
        var main = player.getInventory().main;
        for (ItemStack st : main) {
            if (st == null || st.isEmpty()) continue;
            if (st.getItem() == item) c += st.getCount();
        }
        return c;
    }
}

