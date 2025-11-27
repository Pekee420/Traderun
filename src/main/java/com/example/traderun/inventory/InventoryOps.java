package com.example.traderun.inventory;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.BlockItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;

public final class InventoryOps {

    private static final int SAFE_HOTBAR_SLOT = 8; // Reserve last hotbar slot for empty hand buffer

    private InventoryOps() {}

    /**
     * Counts empty slots in main inventory (includes hotbar). Excludes armor/offhand.
     */
    public static int emptyMainSlots(PlayerEntity player) {
        if (player == null) return 0;
        int empty = 0;
        for (int i = 0; i < player.getInventory().main.size(); i++) {
            ItemStack s = player.getInventory().main.get(i);
            if (s == null || s.isEmpty()) empty++;
        }
        return empty;
    }

    public static int countItem(PlayerEntity player, Item item) {
        if (player == null || item == null) return 0;
        int count = 0;
        for (int i = 0; i < player.getInventory().main.size(); i++) {
            ItemStack s = player.getInventory().main.get(i);
            if (s == null || s.isEmpty()) continue;
            if (s.getItem() == item) count += s.getCount();
        }
        return count;
    }

    public static boolean hasItem(PlayerEntity player, Item item) {
        return countItem(player, item) > 0;
    }

    public static boolean ensureFreeHand(PlayerEntity player, Identifier preferredItemId) {
        if (player == null) return false;
        // preferredItemId currently unused but kept for API compatibility
        if (preferredItemId != null) {
            // Intentionally no-op
        }
        PlayerInventory inv = player.getInventory();
        DefaultedList<ItemStack> main = inv.main;

        // Always prefer the reserved safe slot; keep it empty if possible
        if (ensureSafeSlotEmpty(main)) {
            inv.selectedSlot = SAFE_HOTBAR_SLOT;
            return true;
        }

        // Safe slot occupied and no place to move items - fall back to non-block hand
        int nonBlockHotbar = findHotbarSlotWithNonBlock(main);
        if (nonBlockHotbar >= 0) {
            inv.selectedSlot = nonBlockHotbar;
            return true;
        }

        // Last fallback: use any empty hotbar slot
        int emptyHotbar = findEmptyHotbarSlot(main);
        if (emptyHotbar >= 0) {
            inv.selectedSlot = emptyHotbar;
            return true;
        }

        // Could not guarantee safe hand
        return inv.getMainHandStack().isEmpty();
    }

    private static int findEmptyHotbarSlot(DefaultedList<ItemStack> main) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = main.get(slot);
            if (stack == null || stack.isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    private static int findHotbarSlotWithNonBlock(DefaultedList<ItemStack> main) {
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = main.get(slot);
            if (stack != null && !stack.isEmpty() && !(stack.getItem() instanceof BlockItem)) {
                return slot;
            }
        }
        return -1;
    }

    private static boolean ensureSafeSlotEmpty(DefaultedList<ItemStack> main) {
        ItemStack stack = main.get(SAFE_HOTBAR_SLOT);
        if (stack == null || stack.isEmpty()) {
            if (stack == null) {
                main.set(SAFE_HOTBAR_SLOT, ItemStack.EMPTY);
            }
            return true;
        }

        int emptySlot = findEmptyInventorySlot(main);
        if (emptySlot >= 0) {
            main.set(emptySlot, stack);
            main.set(SAFE_HOTBAR_SLOT, ItemStack.EMPTY);
            return true;
        }

        // No empty slots - try swapping with a non-block slot
        int nonBlockSlot = findInventorySlotWithNonBlock(main);
        if (nonBlockSlot >= 0) {
            ItemStack other = main.get(nonBlockSlot);
            main.set(nonBlockSlot, stack);
            main.set(SAFE_HOTBAR_SLOT, other);
            if (main.get(SAFE_HOTBAR_SLOT) == null) {
                main.set(SAFE_HOTBAR_SLOT, ItemStack.EMPTY);
            }
            return main.get(SAFE_HOTBAR_SLOT).isEmpty();
        }

		// nothing we can do
		return false;
    }

    private static int findEmptyInventorySlot(DefaultedList<ItemStack> main) {
        for (int slot = 9; slot < main.size(); slot++) {
            ItemStack stack = main.get(slot);
            if (stack == null || stack.isEmpty()) {
                return slot;
            }
        }
        return -1;
    }

    private static int findInventorySlotWithNonBlock(DefaultedList<ItemStack> main) {
        for (int slot = 9; slot < main.size(); slot++) {
            ItemStack stack = main.get(slot);
            if (stack != null && !stack.isEmpty() && !(stack.getItem() instanceof BlockItem)) {
                return slot;
            }
        }
        return -1;
    }
}

