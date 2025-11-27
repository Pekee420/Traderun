package com.example.traderun.fsm;

import com.example.traderun.config.TradeRunSettings;
import com.example.traderun.cooldown.CooldownRegistry;
import com.example.traderun.cooldown.InteractedVillagerRegistry;
import com.example.traderun.cooldown.RecentFailRegistry;
import com.example.traderun.cooldown.RestockWatcher;
import com.example.traderun.floor.FloorRegistry;
import com.example.traderun.inventory.ContainerOps;
import com.example.traderun.inventory.InventoryOps;
import com.example.traderun.nav.Navigator;
import com.example.traderun.storage.StorageRegistry;
import com.example.traderun.storage.StorageRegistry.Role;
import com.example.traderun.util.DebugLogger;
import com.example.traderun.villager.VillagerFinder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.ingame.MerchantScreen;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.item.Item;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Random;

public class TradeRunStateMachine {

    public enum State {
        IDLE,
        DETOUR_RESTOCK,
        DETOUR_DUMP,
        RETURN_INPUT,  // Return excess input items to chest before switching floors
        SEEK,
        APPROACH,
        OPEN_ATTEMPTS,
        NUDGE_FORWARD,
        WAIT_CLOSE,
        FLOOR_TRANSITION
    }

    private enum ApproachKind {
        VILLAGER,
        INPUT_CHEST,
        OUTPUT_CHEST
    }

    private static final double APPROACH_GOAL_RANGE = 0.75;
    private static final double APPROACH_GOAL_RANGE_SQ = APPROACH_GOAL_RANGE * APPROACH_GOAL_RANGE;

    private static final double INTERACT_RANGE_VILLAGER = 7.0;
    private static final double INTERACT_RANGE_VILLAGER_SQ = INTERACT_RANGE_VILLAGER * INTERACT_RANGE_VILLAGER;

    private static final double INTERACT_RANGE_CONTAINER = 5.0;
    private static final double INTERACT_RANGE_CONTAINER_SQ = INTERACT_RANGE_CONTAINER * INTERACT_RANGE_CONTAINER;

    private static final long OPEN_TIMEOUT_MS = 7000L;
    private static final long APPROACH_TIMEOUT_MS = 8000L;
    private static final long STORAGE_NAV_TIMEOUT_MS = 12000L;  // Longer timeout for storage runs

    private static final long USE_PRESS_MS = 80L;
    private static final long AIM_DELAY_MS = 80L;

    private static final long STRAIGHT_APPROACH_HANG_MS = 800L;  // 800ms before trying alternate
    private static final double HANG_MIN_PROGRESS_DISTSQ = 0.02 * 0.02;
    private static final double HANG_MIN_IMPROVE_DISTSQ = 0.04;

    private static final long DIAGONAL_RETRY_MS = 400L;  // Try diagonal for 400ms before nudging

    private static final double NUDGE_FORWARD_DISTANCE = 0.40;
    private static final double NUDGE_FORWARD_DISTANCE_SQ = NUDGE_FORWARD_DISTANCE * NUDGE_FORWARD_DISTANCE;
    private static final int NUDGE_FORWARD_MAX_TICKS = 10;
    private static final int NUDGE_FORWARD_MAX_TIMES = 1;  // Only nudge once, then give up

    private static final int DUMP_TRIGGER_EMPTY_SLOTS = 2;  // Keep 2 slots free
    private static final int RESTOCK_RESERVED_EMPTY_SLOTS = 1;

    private static final long POST_CONTAINER_CLOSE_COOLDOWN_MS = 800L;
    private long blockContainerInteractUntilMs = 0L;

    // ===== Watchdogs =====
    private static final long RECOVER_NO_MOVE_MS = 2000L;
    private static final long FAIL_NO_MOVE_MS = 5000L;
    private static final double MOVE_EPS_SQ = 0.05 * 0.05;

    private Vec3d lastMovePos = null;
    private long lastMoveMs = 0L;
    private long lastRecoverAttemptMs = 0L;
    private long firstStallMs = 0L; // When stall was first detected (doesn't reset on recovery)

    private final Deque<String> debugLines = new ArrayDeque<>();
    private static final int DEBUG_MAX = 50;
    private State lastDbgState = null;

    // Remembered per session (persisted per-floor via StorageRegistry)
    private Identifier learnedInputItemId = null;
    private Identifier learnedOutputItemId = null;
    
    // The primary floor Y for storage lookups - set when trade run starts
    private Integer primaryFloorY = null;

    private long nextRestockAllowedMs = 0L;
    private long lastRestockNoticeMs = 0L;

    private State state = State.IDLE;

    private final Navigator navigator = new Navigator();
    private final VillagerFinder villagerFinder = new VillagerFinder();

    private VillagerEntity currentTarget;
    private BlockPos currentApproachGoal;
    private ApproachKind approachKind = ApproachKind.VILLAGER;
    private BlockPos currentChestPos = null;
    private BlockPos currentChestOpenSpot = null;  // Saved position to stand when opening chest

    private long approachStartMs = 0L;
    private Vec3d approachStartPlayerPos = null;
    private double approachStartGoalDistSq = 0.0;
    private boolean attemptedDiagonalFromHang = false;
    
    // Rolling stuck detection - check every 1.5s if we've made progress
    private long lastStuckCheckMs = 0L;
    private Vec3d lastStuckCheckPos = null;
    private int stuckCheckFailCount = 0;
    private static final long STUCK_CHECK_INTERVAL_MS = 1500L;
    private static final double STUCK_MIN_MOVEMENT_SQ = 0.1 * 0.1; // Must move at least 0.1 blocks
    private static final int STUCK_FAIL_THRESHOLD = 2; // Fail after 2 consecutive stuck checks (3 seconds)

    private boolean useKeyHeld = false;
    private long lastUseToggleMs = 0L;

    private boolean forwardKeyForced = false;

    private long firstOpenAttemptMs = 0L;
    private long lastAimMs = 0L;
    private boolean cooldownRegistered = false;

    private boolean usingDiagonal = false;

    private int nudgeTimes = 0;
    private int nudgeTicksRemaining = 0;
    private Vec3d nudgeStartPos = null;

    private ContainerOps.Session containerSession = null;
    private long containerOpenFirstAttemptMs = 0L;
    private long containerLastInteractMs = 0L;
    private final Random rng = new Random();

    public State getState() { return state; }
    public boolean isActive() { return state != State.IDLE; }

    private void dbg(String msg) {
        if (msg == null) return;
        if (debugLines.size() >= DEBUG_MAX) debugLines.removeFirst();
        debugLines.addLast("[" + java.time.LocalTime.now().toString().substring(0,8) + "] " + msg);
        DebugLogger.log(msg);  // Also log to file
    }
    
    /** Get the last N debug lines for external use */
    public List<String> getDebugLines(int count) {
        List<String> result = new ArrayList<>();
        Iterator<String> it = debugLines.descendingIterator();
        while (it.hasNext() && result.size() < count) {
            result.add(0, it.next());
        }
        return result;
    }

    private void say(MinecraftClient c, String msg) {
        if (c != null && c.player != null) c.player.sendMessage(Text.literal("[traderun] " + msg), false);
    }
    
    /** Show short status in hotbar/actionbar */
    private void status(MinecraftClient c, String msg) {
        if (c != null && c.player != null) c.player.sendMessage(Text.literal("§7" + msg), true);
    }
    
    private String lastStatus = "";
    private long lastStatusMs = 0L;
    
    /** Show status but rate-limit to avoid spam (updates every 500ms) */
    private void statusThrottled(MinecraftClient c, String msg) {
        long now = System.currentTimeMillis();
        if (!msg.equals(lastStatus) || now - lastStatusMs > 500L) {
            status(c, msg);
            lastStatus = msg;
            lastStatusMs = now;
        }
    }

    private void closeAnyScreenProperly(MinecraftClient client) {
        if (client == null) return;
        if (client.player != null) {
            try { client.player.closeHandledScreen(); } catch (Throwable ignored) {}
        }
        try { client.setScreen(null); } catch (Throwable ignored) {}
        blockContainerInteractUntilMs = System.currentTimeMillis() + POST_CONTAINER_CLOSE_COOLDOWN_MS;
    }

    public void startForProfession(String professionKey) {
        villagerFinder.setTargetProfessionId(professionKey);
        InteractedVillagerRegistry.clear();
        RecentFailRegistry.fullReset();  // Full reset on new start

        resetAllTransient();
        nextRestockAllowedMs = 0L;
        lastRestockNoticeMs = 0L;
        
        // Set primary floor Y from registered floor
        primaryFloorY = null;
        Optional<FloorRegistry.FloorInfo> floorInfo = FloorRegistry.getFloorForProfession(professionKey);
        if (floorInfo.isPresent()) {
            primaryFloorY = floorInfo.get().y;
        } else {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.player != null) {
                primaryFloorY = client.player.getBlockPos().getY();
            }
        }

        state = State.SEEK;
        arrivedOnFloorMs = System.currentTimeMillis();
        dbg("start(" + professionKey + ") primaryFloorY=" + primaryFloorY);
        say(MinecraftClient.getInstance(), "started (" + professionKey + ")");
    }
    
    public void startForProfessions(java.util.List<String> professions) {
        DebugLogger.clear();  // Clear old log on fresh start
        DebugLogger.log("=== TRADERUN START ===");
        DebugLogger.log("Professions: " + professions);
        
        InteractedVillagerRegistry.clear();
        RecentFailRegistry.fullReset();  // Full reset on new start

        resetAllTransient();
        nextRestockAllowedMs = 0L;
        lastRestockNoticeMs = 0L;

        MinecraftClient client = MinecraftClient.getInstance();
        String profsStr = String.join(", ", professions);
        
        // Build floor names list from professions that have registered floors with storage
        activeFloorNames.clear();
        List<FloorRegistry.FloorInfo> matchingFloors = new ArrayList<>();
        java.util.Set<Integer> seenFloorYs = new java.util.HashSet<>();
        for (String prof : professions) {
            Optional<FloorRegistry.FloorInfo> floorInfo = FloorRegistry.getFloorByName(prof);
            if (floorInfo.isPresent()) {
                FloorRegistry.FloorInfo f = floorInfo.get();
                // Check if floor has storage
                if (StorageRegistry.hasStorageForFloor(f.y) && seenFloorYs.add(f.y)) {
                    // Use floor name if set, otherwise use profession
                    String floorName = (f.name != null && !f.name.isEmpty()) ? f.name : prof;
                    activeFloorNames.add(floorName);
                    matchingFloors.add(f);
                } else if (StorageRegistry.hasStorageForFloor(f.y)) {
                    dbg("start: skipping duplicate floor for profession " + prof + " at Y=" + f.y);
                }
            }
        }
        currentFloorIndex = 0;
        
        dbg("Built activeFloorNames from professions: " + activeFloorNames);
        
        // Find the primary floor for storage lookups (from registered floors)
        primaryFloorY = null;
        FloorRegistry.FloorInfo firstFloor = null;
        if (!matchingFloors.isEmpty()) {
            firstFloor = matchingFloors.get(0);
            primaryFloorY = firstFloor.y;
            // Set professions for ONLY the first floor
            villagerFinder.setTargetProfessions(new ArrayList<>(firstFloor.professions));
            villagerFinder.setTargetFloorY(firstFloor.y);
            dbg("Primary floor Y set to " + primaryFloorY + " with profs=" + firstFloor.professions);
        } else {
            // No matching floors - use all professions
            villagerFinder.setTargetProfessions(professions);
            villagerFinder.setTargetFloorY(null); // Use player Y as fallback
        }
        
        // Fallback: find ANY floor with storage if no registered floor found
        if (primaryFloorY == null) {
            int refY = (client != null && client.player != null) ? client.player.getBlockPos().getY() : 64;
            Optional<Integer> anyFloorY = StorageRegistry.getAnyFloorWithBothStorage(refY);
            if (anyFloorY.isPresent()) {
                primaryFloorY = anyFloorY.get();
                dbg("Primary floor Y set to " + primaryFloorY + " (from storage registry fallback)");
            } else {
                // Last resort - use player's current Y
                if (client != null && client.player != null) {
                    primaryFloorY = client.player.getBlockPos().getY();
                    dbg("Primary floor Y set to player Y=" + primaryFloorY + " (no registered floor or storage)");
                }
            }
        }
        
        // Check if we need to navigate to a registered floor for this profession
        if (client != null && client.player != null && firstFloor != null) {
            int playerY = client.player.getBlockPos().getY();
            
            if (Math.abs(firstFloor.y - playerY) > 1) {
                // Not on the right floor - set up floor transition
                targetFloorY = firstFloor.y;
                BlockPos target = new BlockPos(firstFloor.clusterX, firstFloor.y, firstFloor.clusterZ);
                say(client, "started (" + profsStr + ") - navigating to floor Y=" + firstFloor.y);
                dbg("start(" + profsStr + ") - need floor Y=" + firstFloor.y + ", at Y=" + playerY);
                floorTransitionStartY = client.player != null ? client.player.getBlockPos().getY() : firstFloor.y;
                floorTransitionStartMs = System.currentTimeMillis();
                state = State.FLOOR_TRANSITION;
                floorTransitionPhase = 0;
                transitionPoint = target;
                return;
            }
        }

        state = State.SEEK;
        arrivedOnFloorMs = System.currentTimeMillis();
        dbg("start(" + profsStr + ")");
        say(client, "started (" + profsStr + ")");
        if (activeFloorNames.size() > 1) {
            say(client, "Multi-floor mode: " + String.join(", ", activeFloorNames));
        }
    }
    
    // Floor-based trading
    private List<String> activeFloorNames = new ArrayList<>();
    private int currentFloorIndex = 0;
    
    // Return input items before floor switch
    private static final int RETURN_INPUT_THRESHOLD = 192;  // 3 stacks
    private static final long MIN_TIME_ON_FLOOR_MS = 5000L;  // Stay at least 5s on a floor before switching
    private String pendingFloorSwitchName = null;
    private int pendingFloorSwitchIndex = 0;
    private Identifier itemToReturn = null;
    private long arrivedOnFloorMs = 0L;  // When we last arrived on current floor
    
    /**
     * Start trading on specific floors by name.
     * Will automatically switch to next floor when all villagers on current floor are on cooldown.
     */
    public String startForFloorNames(java.util.List<String> floorNames) {
        DebugLogger.clear();
        DebugLogger.log("=== TRADERUN START (FLOORS) ===");
        DebugLogger.log("Floor names: " + floorNames);
        
        // Validate floor names and collect professions
        List<String> validNames = new ArrayList<>();
        List<String> professions = new ArrayList<>();
        StringBuilder errors = new StringBuilder();
        List<String> noStorageFloors = new ArrayList<>();
        
        for (String name : floorNames) {
            Optional<FloorRegistry.FloorInfo> floor = FloorRegistry.getFloorByName(name);
            if (floor.isPresent()) {
                FloorRegistry.FloorInfo f = floor.get();
                // Check if floor has storage configured
                if (!StorageRegistry.hasStorageForFloor(f.y)) {
                    noStorageFloors.add(name + " (Y=" + f.y + ")");
                } else {
                    validNames.add(name);
                    professions.addAll(f.professions);
                }
            } else {
                if (errors.length() > 0) errors.append(", ");
                errors.append(name);
            }
        }
        
        // Report floors missing storage
        if (!noStorageFloors.isEmpty()) {
            String missing = String.join(", ", noStorageFloors);
            if (validNames.isEmpty()) {
                return "No floors have storage configured. Missing: " + missing + 
                       ". Use /traderun storage input and /traderun storage output on each floor.";
            }
            // Continue with valid floors, but warn about missing ones
            DebugLogger.log("Floors missing storage (skipped): " + missing);
        }
        
        if (validNames.isEmpty()) {
            return "No valid floor names found: " + errors;
        }
        
        // Store active floors (dedupe by Y level so the same floor isn't added twice)
        activeFloorNames = new ArrayList<>();
        currentFloorIndex = 0;
        java.util.Set<Integer> seenFloorYs = new java.util.HashSet<>();
        for (String name : validNames) {
            Optional<FloorRegistry.FloorInfo> fOpt = FloorRegistry.getFloorByName(name);
            if (fOpt.isPresent()) {
                FloorRegistry.FloorInfo f = fOpt.get();
                if (seenFloorYs.add(f.y)) {
                    activeFloorNames.add(name);
                } else {
                    dbg("start floors: skipping duplicate floor '" + name + "' at Y=" + f.y);
                }
            }
        }
        if (activeFloorNames.isEmpty()) {
            return "No unique floors found after removing duplicates.";
        }
        
        InteractedVillagerRegistry.clear();
        RecentFailRegistry.fullReset();  // Full reset on new start
        resetAllTransient();
        nextRestockAllowedMs = 0L;
        lastRestockNoticeMs = 0L;
        
        MinecraftClient client = MinecraftClient.getInstance();
        
        // Start on first floor - only target THAT floor's professions
        FloorRegistry.FloorInfo firstFloor = FloorRegistry.getFloorByName(activeFloorNames.get(0)).get();
        
        // Set up VillagerFinder with ONLY the first floor's professions
        villagerFinder.setTargetProfessions(new ArrayList<>(firstFloor.professions));
        villagerFinder.setTargetFloorY(firstFloor.y);
        DebugLogger.log("Starting on floor " + activeFloorNames.get(0) + " with professions: " + firstFloor.professions);
        primaryFloorY = firstFloor.y;
        
        if (client != null && client.player != null) {
            int playerY = client.player.getBlockPos().getY();
            
            if (Math.abs(firstFloor.y - playerY) > 1) {
                // Navigate to first floor
                targetFloorY = firstFloor.y;
                BlockPos target = new BlockPos(firstFloor.clusterX, firstFloor.y, firstFloor.clusterZ);
                floorTransitionStartY = client.player != null ? client.player.getBlockPos().getY() : firstFloor.y;
                floorTransitionStartMs = System.currentTimeMillis();
                state = State.FLOOR_TRANSITION;
                floorTransitionPhase = 0;
                transitionPoint = target;
                dbg("start floors: navigating to " + activeFloorNames.get(0) + " Y=" + firstFloor.y);
                String navResult = "Started (floors: " + String.join(", ", validNames) + ") - navigating to " + activeFloorNames.get(0);
                if (!noStorageFloors.isEmpty()) {
                    navResult += " - skipped (no storage): " + String.join(", ", noStorageFloors);
                }
                return navResult;
            }
        }
        
        state = State.SEEK;
        String result = "Started (floors: " + String.join(", ", validNames) + ")";
        if (errors.length() > 0) {
            result += " - unknown floors: " + errors;
        }
        if (!noStorageFloors.isEmpty()) {
            result += " - skipped (no storage): " + String.join(", ", noStorageFloors);
        }
        dbg("start floors: " + result);
        return result;
    }
    
    /**
     * Try to switch to next floor in the list.
     * Called when all villagers on current floor are on cooldown.
     * @return true if switching floors, false if staying
     */
    private boolean tryNextFloor(MinecraftClient client) {
        if (activeFloorNames.isEmpty() || activeFloorNames.size() <= 1) {
            return false;
        }
        
        // Don't switch floors too quickly - give time to trade
        long timeSinceArrival = System.currentTimeMillis() - arrivedOnFloorMs;
        if (arrivedOnFloorMs > 0 && timeSinceArrival < MIN_TIME_ON_FLOOR_MS) {
            dbg("tryNextFloor: staying on floor, only " + timeSinceArrival + "ms since arrival");
            return false;
        }
        
        // Find next floor with available villagers
        int startIndex = currentFloorIndex;
        for (int i = 1; i <= activeFloorNames.size(); i++) {
            int nextIndex = (startIndex + i) % activeFloorNames.size();
            String nextName = activeFloorNames.get(nextIndex);
            Optional<FloorRegistry.FloorInfo> floorOpt = FloorRegistry.getFloorByName(nextName);
            
            if (floorOpt.isPresent()) {
                FloorRegistry.FloorInfo floor = floorOpt.get();
                
                    if (primaryFloorY != null && Math.abs(floor.y - primaryFloorY) <= 0) {
                        dbg("Skip floor " + nextName + " Y=" + floor.y + " - same Y as current");
                        continue;
                    }
                    
                // Check if this floor has storage configured before switching
                if (!StorageRegistry.hasStorageForFloor(floor.y)) {
                    dbg("Skip floor " + nextName + " Y=" + floor.y + " - no storage configured");
                    say(client, "Floor " + nextName + " has no storage set up - skipping");
                    continue;  // Try next floor
                }
                
                if (nextIndex != currentFloorIndex) {
                    // Check if we have >3 stacks of current floor's input item
                    int currentY = currentFloorKeyY(client);
                    Identifier currentInputId = StorageRegistry.getRememberedItem(Role.INPUT, currentY).orElse(learnedInputItemId);
                    int haveCurrentInput = (currentInputId == null) ? 0 : countItemById(client, currentInputId);
                    
                    if (haveCurrentInput > RETURN_INPUT_THRESHOLD && !outputChestFull) {
                        // Need to return items first (but only if output isn't full - otherwise keep trading)
                        dbg("Have " + haveCurrentInput + " input items (>" + RETURN_INPUT_THRESHOLD + "), returning to input chest first");
                        say(client, "Returning " + haveCurrentInput + " items to input before switching");
                        
                        // Save pending floor switch info
                        pendingFloorSwitchName = nextName;
                        pendingFloorSwitchIndex = nextIndex;
                        itemToReturn = currentInputId;
                        
                        // Navigate to current floor's input chest
                        currentChestPos = null;
                        currentChestOpenSpot = null;
                        currentApproachGoal = null;
                        containerSession = null;
                        state = State.RETURN_INPUT;
                        return true;
                    }
                    
                    // Normal floor switch (no excess items)
                    currentFloorIndex = nextIndex;
                    primaryFloorY = floor.y;
                    targetFloorY = floor.y;
                    transitionPoint = new BlockPos(floor.clusterX, floor.y, floor.clusterZ);
                    floorTransitionPhase = 0;
                    floorTransitionStartY = client != null && client.player != null ? client.player.getBlockPos().getY() : floor.y;
                    floorTransitionStartMs = System.currentTimeMillis();
                    state = State.FLOOR_TRANSITION;
                    
                    // Update target professions to ONLY the new floor's professions
                    villagerFinder.setTargetProfessions(new ArrayList<>(floor.professions));
                    villagerFinder.setTargetFloorY(floor.y);
                    // Clear session-learned items to prevent cross-floor contamination
                    learnedInputItemId = null;
                    learnedOutputItemId = null;
                    dbg("Switching to floor: " + nextName + " Y=" + floor.y + " profs=" + floor.professions);
                    say(client, "Switching to floor: " + nextName);
                    return true;
                }
            }
        }
        
        return false;
    }

    public void stop() {
        navigator.stop();
        releaseUseKey(MinecraftClient.getInstance());
        releaseForwardKey(MinecraftClient.getInstance());
        resetAllTransient();
        primaryFloorY = null;  // Clear on stop
        villagerFinder.setTargetFloorY(null);  // Clear floor lock
        activeFloorNames.clear();  // Clear floor names mode
        currentFloorIndex = 0;
        state = State.IDLE;
        dbg("stop()");
    }

    public void abortHard(MinecraftClient client) {
        navigator.stop();
        releaseUseKey(client);
        releaseForwardKey(client);
        closeAnyScreenProperly(client);
        resetAllTransient();
        primaryFloorY = null;  // Clear on abort
        activeFloorNames.clear();
        currentFloorIndex = 0;
        state = State.IDLE;
        dbg("abortHard()");
    }

    /** Called when ChatScreen opens - release all forced keys so user can type. */
    public void releaseAllForcedKeys(MinecraftClient client) {
        releaseUseKey(client);
        releaseForwardKey(client);
    }

    public void tick(MinecraftClient client) {
        if (client == null) return;

        // Always release keys when IDLE or chat is open
        if (state == State.IDLE) {
            releaseUseKey(client);
            releaseForwardKey(client);
            return;
        }

        // Don't process while chat is open
        if (client.currentScreen instanceof ChatScreen) {
            releaseUseKey(client);
            releaseForwardKey(client);
            return;
        }

        // Shift to abort
        if (client.options != null && client.options.sneakKey != null && client.options.sneakKey.isPressed()) {
            dbg("abort: sneak");
            abortHard(client);
            return;
        }

        // Only allow Y-level changes during floor transitions
        // Storage runs (DETOUR_RESTOCK/DUMP) should stay on same floor - detect falls!
        boolean allowYChanges = (state == State.FLOOR_TRANSITION);
        navigator.setAllowYLevelChanges(allowYChanges);
        
        // Tick direct walk fallback if Baritone isn't available
        navigator.tickDirectWalk(client);

        updateMovementWatch(client);

        if (client.player != null && shouldEnforceFloorLock()) {
            int playerY = client.player.getBlockPos().getY();
            if (ensureOnActiveFloor(client, playerY)) {
                return;
            }
        }

        if (state != lastDbgState) {
            dbg("state->" + state);
            lastDbgState = state;
            // Reset movement timer on state change to prevent false stall detection
            lastMoveMs = System.currentTimeMillis();
            lastMovePos = client.player != null ? client.player.getPos() : null;
            lastRecoverAttemptMs = 0L;
        }

        // General stall detection for ALL active states
        if (state != State.IDLE) {
            long now = System.currentTimeMillis();
            
            // Track when stall first started (doesn't reset on recovery)
            if ((now - lastMoveMs) >= RECOVER_NO_MOVE_MS) {
                if (firstStallMs == 0L) {
                    firstStallMs = now;
                }
            } else {
                firstStallMs = 0L; // Reset if we're moving
            }
            
            // 2-second stall recovery (for APPROACH, DETOUR_DUMP, DETOUR_RESTOCK when navigating)
            boolean isNavigatingState = (state == State.APPROACH || state == State.DETOUR_DUMP || state == State.DETOUR_RESTOCK);
            if (isNavigatingState && client.currentScreen == null) {
                // For DETOUR states, also try recovery if goal not set yet (nav might have failed)
                boolean hasGoal = currentApproachGoal != null;
                boolean stillFarFromGoal = hasGoal && distSqToGoal(client, currentApproachGoal) > APPROACH_GOAL_RANGE_SQ;
                boolean shouldRecover = (hasGoal && stillFarFromGoal) || (!hasGoal && (state == State.DETOUR_DUMP || state == State.DETOUR_RESTOCK));
                
                if (shouldRecover && (now - lastMoveMs) >= RECOVER_NO_MOVE_MS && (now - lastRecoverAttemptMs) >= RECOVER_NO_MOVE_MS) {
                    lastRecoverAttemptMs = now;
                    dbg("recover: stall 2s (state=" + state + " goal=" + (hasGoal ? "set" : "null") + ")");
                    recoverNavigation(client);
                    // Reset firstStallMs to give recovery time to work
                    firstStallMs = 0L;
                }
            }

            // 5-second absolute timeout for ANY state - but NOT when a screen is open (we're in a container)
            // Also skip if we have an active container session
            boolean inContainerInteraction = (client.currentScreen != null) || (containerSession != null);
            if (firstStallMs > 0L && (now - firstStallMs) >= FAIL_NO_MOVE_MS && !inContainerInteraction) {
                String failInfo = "FAIL 5s: state=" + state + " approach=" + approachKind +
                    " baritone=" + navigator.isBaritoneAvailable() + 
                    " goto=" + navigator.wasGotoIssued() + 
                    " direct=" + navigator.isDirectWalkActive() +
                    " goal=" + (currentApproachGoal != null ? currentApproachGoal.toShortString() : "null") +
                    " err=" + navigator.getLastError() +
                    " target=" + (currentTarget != null ? "yes" : "no") +
                    " screen=" + (client.currentScreen != null ? client.currentScreen.getClass().getSimpleName() : "null");
                dbg(failInfo);
                DebugLogger.error(failInfo);  // Auto-save debug file
                say(client, "FAIL: stalled 5s - debug saved to config/traderun_error_*.txt");
                firstStallMs = 0L;
                stop();
                return;
            }
        }

        switch (state) {
            case IDLE -> {}
            case DETOUR_RESTOCK -> tickDetourRestock(client);
            case DETOUR_DUMP -> tickDetourDump(client);
            case RETURN_INPUT -> tickReturnInput(client);
            case SEEK -> tickSeek(client);
            case APPROACH -> tickApproach(client);
            case OPEN_ATTEMPTS -> tickOpenAttempts(client);
            case NUDGE_FORWARD -> tickNudgeForward(client);
            case WAIT_CLOSE -> tickWaitClose(client);
            case FLOOR_TRANSITION -> tickFloorTransition(client);
        }
    }

    private void updateMovementWatch(MinecraftClient client) {
        if (client.player == null) return;
        long now = System.currentTimeMillis();
        Vec3d p = client.player.getPos();
        if (lastMovePos == null) {
            lastMovePos = p;
            lastMoveMs = now;
            return;
        }

        double dx = p.x - lastMovePos.x;
        double dz = p.z - lastMovePos.z;
        double d2 = dx * dx + dz * dz;
        if (d2 >= MOVE_EPS_SQ) {
            lastMovePos = p;
            lastMoveMs = now;
        }
    }

    private void recoverNavigation(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) return;

        navigator.stop();

        // For DETOUR states, just clear navigation and re-try on next tick
        if (state == State.DETOUR_DUMP || state == State.DETOUR_RESTOCK) {
            dbg("recover: re-nav for " + state);
            currentApproachGoal = null;  // Force re-navigation next tick
            // Force navigator to re-issue goal by clearing its state and rate limit
            navigator.clearRateLimit();
            return;  // Stay in same state, will retry navigation
        }

        // Simple recovery: mark current target as failed if villager, then go to SEEK with delay
        if (approachKind == ApproachKind.VILLAGER && currentTarget != null) {
            dbg("recover: marking current villager as failed");
            RecentFailRegistry.markFailure(currentTarget);
        }
        
        // Clear approach state but NOT chest positions (those are from config)
        currentTarget = null;
        currentApproachGoal = null;
        // Don't clear currentChestPos - it will be re-acquired from StorageRegistry
        // Don't clear containerSession - let it finish or timeout naturally
        nextSeekAllowedMs = System.currentTimeMillis() + 1500L; // 1.5 second delay
        state = State.SEEK;
        dbg("recover: -> SEEK (waiting 1.5s)");
    }

    private void resetAllTransient() {
        currentTarget = null;
        currentApproachGoal = null;
        approachKind = ApproachKind.VILLAGER;
        currentChestPos = null;
        currentChestOpenSpot = null;

        approachStartMs = 0L;
        approachStartPlayerPos = null;
        approachStartGoalDistSq = 0.0;
        attemptedDiagonalFromHang = false;

        useKeyHeld = false;
        lastUseToggleMs = 0L;

        forwardKeyForced = false;

        firstOpenAttemptMs = 0L;
        lastAimMs = 0L;
        cooldownRegistered = false;

        usingDiagonal = false;

        nudgeTimes = 0;
        nudgeTicksRemaining = 0;
        nudgeStartPos = null;

        containerSession = null;
        containerOpenFirstAttemptMs = 0L;
        containerLastInteractMs = 0L;

        blockContainerInteractUntilMs = 0L;

        lastMovePos = null;
        lastMoveMs = System.currentTimeMillis();
        lastRecoverAttemptMs = 0L;
        firstStallMs = 0L;
        outputChestFull = false;
        inputChestEmpty = false;
        nextRestockCheckMs = 0L;
        waitStartMs = 0L;
        waitReason = null;
        emptyInputFloorSwitches = 0;
        emptyInputRotationCount = 0;
        emptyInputWaitCycles = 0;

        debugLines.clear();
        lastDbgState = null;
    }

    private void resetOpenAttemptState() {
        firstOpenAttemptMs = 0L;
        lastAimMs = 0L;
        cooldownRegistered = false;
        useKeyHeld = false;
        lastUseToggleMs = 0L;
        nudgeTicksRemaining = 0;
        nudgeStartPos = null;
    }

    private void resetApproachTracking(MinecraftClient client) {
        approachStartMs = System.currentTimeMillis();
        approachStartPlayerPos = (client.player == null) ? null : client.player.getPos();
        approachStartGoalDistSq = (client.player == null || currentApproachGoal == null) ? 0.0 : distSqToGoal(client, currentApproachGoal);
        // Reset rolling stuck detection
        lastStuckCheckMs = approachStartMs;
        lastStuckCheckPos = approachStartPlayerPos;
        stuckCheckFailCount = 0;
    }

    private void resetApproachTracking() {
        approachStartMs = 0L;
        approachStartPlayerPos = null;
        approachStartGoalDistSq = 0.0;
        // Reset rolling stuck detection
        lastStuckCheckMs = 0L;
        lastStuckCheckPos = null;
        stuckCheckFailCount = 0;
    }

    private void backoffRestock(MinecraftClient client, String reason) {
        long now = System.currentTimeMillis();
        nextRestockAllowedMs = now + 3500L;
        if (now - lastRestockNoticeMs > 1200L) {
            say(client, reason);
            lastRestockNoticeMs = now;
        }
    }

    private boolean canInteractWithContainer(MinecraftClient client, BlockPos chestPos) {
        if (client == null || client.player == null || chestPos == null) return false;
        Vec3d p = client.player.getEyePos();
        Vec3d c = new Vec3d(chestPos.getX() + 0.5, chestPos.getY() + 0.5, chestPos.getZ() + 0.5);
        return p.squaredDistanceTo(c) <= INTERACT_RANGE_CONTAINER_SQ;
    }

    private int countItemById(MinecraftClient client, Identifier itemId) {
        if (client == null || client.player == null || itemId == null) return 0;
        Item it;
        try { it = Registries.ITEM.get(itemId); } catch (Throwable t) { return 0; }
        if (it == null) return 0;
        return InventoryOps.countItem(client.player, it);
    }

    private int currentFloorKeyY(MinecraftClient client) {
        // Use the primary floor Y set at start (for storage lookups)
        if (primaryFloorY != null) return primaryFloorY;
        // Fallback to current target villager's Y
        if (currentTarget != null) return currentTarget.getBlockPos().getY();
        // Final fallback to player position
        if (client.player != null) return client.player.getBlockPos().getY();
        return 0;
    }

    private void tickDetourRestock(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        long now = System.currentTimeMillis();
        int floorY = currentFloorKeyY(client);
        
        // Only dump output items if inventory is getting full (<=4 empty slots)
        // Don't dump every time we restock - unnecessary trips if we have space
        if (containerSession == null && currentChestPos == null) {
            int emptySlots = InventoryOps.emptyMainSlots(client.player);
            if (emptySlots <= 4 && !outputChestFull) {
                Identifier outId = StorageRegistry.getRememberedItem(Role.OUTPUT, floorY).orElse(learnedOutputItemId);
                if (outId != null) {
                    int outCount = countItemById(client, outId);
                    if (outCount > 0) {
                        Optional<StorageRegistry.StoredLocation> outputLoc = StorageRegistry.getForY(Role.OUTPUT, floorY);
                        if (outputLoc.isPresent()) {
                            dbg("restock: inventory low (" + emptySlots + " empty), dumping " + outCount + " output first");
                            state = State.DETOUR_DUMP;
                            return;
                        }
                    }
                }
            }
        }
        
        // Always ensure chestPos is set first
        if (currentChestPos == null) {
            Optional<StorageRegistry.StoredLocation> locOpt = StorageRegistry.getForY(Role.INPUT, floorY);
            if (locOpt.isEmpty()) {
                // Try to find ANY floor with input storage as fallback
                Optional<Integer> anyFloorY = StorageRegistry.getAnyFloorWithStorage(Role.INPUT, floorY);
                if (anyFloorY.isPresent()) {
                    int newFloorY = anyFloorY.get();
                    dbg("no INPUT at Y=" + floorY + ", found at Y=" + newFloorY + " - updating primaryFloorY");
                    primaryFloorY = newFloorY;
                    floorY = newFloorY;
                    locOpt = StorageRegistry.getForY(Role.INPUT, floorY);
                }
            }
            if (locOpt.isEmpty()) {
                // No INPUT storage anywhere - only show message every 10 seconds
                if (now >= nextRestockAllowedMs) {
                    say(client, "No INPUT storage found! Stand at villager floor & use: /traderun storage set input");
                    nextRestockAllowedMs = now + 10000L;
                }
                state = State.SEEK;
                return;
            }
            currentChestPos = locOpt.get().toBlockPos();
            // Get the saved open spot (where player stood when setting storage)
            Optional<Vec3d> openSpotOpt = StorageRegistry.getOpenSpot(Role.INPUT, floorY);
            if (openSpotOpt.isPresent()) {
                Vec3d spot = openSpotOpt.get();
                // Use floor() for accurate block position from player coordinates
                currentChestOpenSpot = new BlockPos(
                    (int) Math.floor(spot.x), 
                    (int) Math.floor(spot.y), 
                    (int) Math.floor(spot.z)
                );
                dbg("going to INPUT chest via saved spot " + currentChestOpenSpot.toShortString());
            } else {
                currentChestOpenSpot = null;
                dbg("going to INPUT chest at " + currentChestPos.toShortString() + " (no saved spot)");
            }
            statusThrottled(client, "→ Walking to input");
        }
        
        if (now < blockContainerInteractUntilMs) {
            return;
        }

        int inputMin = TradeRunSettings.get().inputMin;

        // If we have an active session, keep ticking it
        if (containerSession != null) {
            tickOpenAndRunContainerSession(client, containerSession);
            
            if (containerSession.done) {
                if (containerSession.inputItemId != null) {
                    learnedInputItemId = containerSession.inputItemId;
                    StorageRegistry.updateRememberedItem(Role.INPUT, floorY, containerSession.inputItemId);
                }

                String error = containerSession.error;
                closeAnyScreenProperly(client);
                containerSession = null;
                currentChestPos = null;
        currentChestOpenSpot = null;
                currentApproachGoal = null;
                approachKind = ApproachKind.VILLAGER;

                // If inventory is full, try to dump output items first
                if (error != null && error.contains("inventory full")) {
                    Optional<StorageRegistry.StoredLocation> outputLoc = StorageRegistry.getForY(Role.OUTPUT, floorY);
                    if (outputLoc.isEmpty()) {
                        say(client, "Inventory full - no OUTPUT storage set (use /traderun storage set output)");
                    } else {
                        dbg("restock: inv full, going to dump");
                        state = State.DETOUR_DUMP;
                        return;
                    }
                }
                
                // If container is empty, enter waiting mode
                if (error != null && error.contains("empty")) {
                    inputChestEmpty = true;
                    nextRestockCheckMs = now + WAIT_CHECK_INTERVAL_MS;
                    if (waitReason == null || !waitReason.equals("input")) {
                        waitStartMs = now;
                        waitReason = "input";
                        lastWaitCheckMs = now;
                        lastWaitMessageMs = 0L;
                        // Save the input chest position for navigating back
                        savedChestPos = currentChestPos;
                        say(client, "Input chest empty - waiting mode (you can move freely)");
                    }
                    closeAnyScreenProperly(client);
                    containerSession = null;
                    currentChestPos = null;
                    currentChestOpenSpot = null;
                    currentApproachGoal = null;
                    state = State.SEEK;
                    dbg("restock: chest empty, entering wait mode (next check in 20s)");
                    if (handleSingleFloorInputEmpty(client)) {
                        return;
                    }
                    return;
                }

                // Other errors - just retry
                if (error != null) {
                    say(client, error);
                }
                
                // Check if we STILL don't have enough items after restock - means chest is depleted
                Identifier checkInputId = StorageRegistry.getRememberedItem(Role.INPUT, floorY).orElse(learnedInputItemId);
                int haveNow = (checkInputId == null) ? 0 : countItemById(client, checkInputId);
                int neededMin = TradeRunSettings.get().inputMin;
                
                if (haveNow < neededMin) {
                    // Still not enough - chest must be empty/depleted, enter wait mode
                    inputChestEmpty = true;
                    nextRestockCheckMs = now + WAIT_CHECK_INTERVAL_MS;
                    if (waitReason == null || !waitReason.equals("input")) {
                        waitStartMs = now;
                        waitReason = "input";
                        lastWaitCheckMs = now;
                        lastWaitMessageMs = 0L;
                        savedChestPos = currentChestPos;
                        say(client, "Input chest depleted (" + haveNow + "/" + neededMin + ") - waiting mode");
                    }
                    state = State.SEEK;
                    dbg("restock: still short on items, entering wait mode (next check in 20s)");
                    if (handleSingleFloorInputEmpty(client)) {
                        return;
                    }
                    return;
                }

                state = State.SEEK;
                dbg("restock done -> SEEK");
            }
            return;
        }

        // No session yet - check if we can interact or if screen is already open
        if (canInteractWithContainer(client, currentChestPos) || client.currentScreen != null) {
            navigator.stop();
            
            // Face the target chest before interacting
            faceBlock(client, currentChestPos);

            containerSession = new ContainerOps.Session(
                    ContainerOps.Mode.WITHDRAW_INPUT_FILL_LEAVE_EMPTY,
                    RESTOCK_RESERVED_EMPTY_SLOTS,
                    inputMin
            );

            StorageRegistry.getRememberedItem(Role.INPUT, floorY).ifPresent(id -> containerSession.inputItemId = id);
            if (containerSession.inputItemId == null && learnedInputItemId != null) containerSession.inputItemId = learnedInputItemId;

            containerOpenFirstAttemptMs = 0L;
            containerLastInteractMs = 0L;

            // Session created, will be ticked next frame
            return;
        }

        if (currentApproachGoal == null) {
            approachKind = ApproachKind.INPUT_CHEST;
            // Use saved open spot if available, otherwise approach chest block
            BlockPos navTarget = (currentChestOpenSpot != null) ? currentChestOpenSpot : currentChestPos;
            currentApproachGoal = navigator.gotoStoragePosition(client, navTarget);
            resetApproachTracking(client);

            if (currentApproachGoal == null) {
                String err = navigator.getLastError();
                backoffRestock(client, "restock blocked: " + (err != null ? err : "can't path to INPUT"));
                currentChestPos = null;
                currentChestOpenSpot = null;
                state = State.SEEK;
            }
            return;
        }

        double dGoal = distSqToGoal(client, currentApproachGoal);
        if (dGoal > APPROACH_GOAL_RANGE_SQ) {
            if (System.currentTimeMillis() - approachStartMs > STORAGE_NAV_TIMEOUT_MS) {
                navigator.stop();
                String dist = String.format("%.1f", Math.sqrt(dGoal));
                dbg("restock nav timeout: couldn't reach input chest in 12s, dist=" + dist);
                say(client, "⚠ Path blocked! Can't reach INPUT chest (dist=" + dist + ")");
                backoffRestock(client, "restock blocked: timeout reaching INPUT container");
                currentChestPos = null;
                currentChestOpenSpot = null;
                currentApproachGoal = null;
                state = State.SEEK;
                dbg("DETOUR_RESTOCK -> SEEK (nav timeout)");
            }
            return;
        }

        // We've reached the nav goal - check if we can interact
        if (!canInteractWithContainer(client, currentChestPos)) {
            // At nav goal but can't interact - try navigating directly to chest
            if (currentChestOpenSpot != null && !currentApproachGoal.equals(currentChestPos)) {
                dbg("at open spot but can't interact, trying direct chest approach");
                currentApproachGoal = navigator.gotoBlockApproach(client, currentChestPos);
                currentChestOpenSpot = null;  // Don't use open spot again
                resetApproachTracking(client);
                return;
            }
        }

        navigator.stop();
        state = State.DETOUR_RESTOCK;
    }

    private long dumpStartMs = 0L;
    private boolean outputChestFull = false; // Track if output chest is full - skip dumping and keep trading
    private boolean inputChestEmpty = false; // Track if input chest is empty - use wait mode
    private long nextDumpAllowedMs = 0L; // Cooldown after dump attempt
    private long nextRestockCheckMs = 0L; // Cooldown after restock attempt when empty
    
    // Waiting mode - check storage periodically for up to 10 minutes
    private static final long WAIT_TIMEOUT_MS = 10 * 60 * 1000L; // 10 minutes
    private static final long WAIT_CHECK_INTERVAL_MS = 20_000L; // Check every 20 seconds
    private static final long WAIT_MESSAGE_INTERVAL_MS = 5_000L; // Show message every 5 seconds
    private long waitStartMs = 0L;
    private long lastWaitCheckMs = 0L;
    private long lastWaitMessageMs = 0L;
    private String waitReason = null; // "input" or "output"
    private BlockPos savedChestPos = null; // Saved chest position to navigate back to
    private static final long DUMP_TIMEOUT_MS = 15000L; // 15 second timeout for dump operation
    private static final int MAX_INPUT_FLOOR_ROTATIONS = 6;
    private static final int MAX_SINGLE_FLOOR_EMPTY_CYCLES = 6;
    private int emptyInputFloorSwitches = 0;
    private int emptyInputRotationCount = 0;
    private int emptyInputWaitCycles = 0;
    
    private void tickDetourDump(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        long now = System.currentTimeMillis();
        if (now < blockContainerInteractUntilMs) return;
        
        // Cooldown after failed dump attempt - go back to SEEK for wait mode handling
        if (now < nextDumpAllowedMs) {
            state = State.SEEK;
            return;
        }
        
        // Initialize dump start time
        if (dumpStartMs == 0L) dumpStartMs = now;
        
        // Timeout - if dump takes too long, abort and continue
        if (now - dumpStartMs > DUMP_TIMEOUT_MS) {
            dbg("dump timeout, closing and continuing");
            closeAnyScreenProperly(client);
            containerSession = null;
            currentChestPos = null;
        currentChestOpenSpot = null;
            currentApproachGoal = null;
            approachKind = ApproachKind.VILLAGER;
            dumpStartMs = 0L;
            state = State.SEEK;
            return;
        }
        
        if (client.currentScreen != null && containerSession == null) return;

        int floorY = currentFloorKeyY(client);

        Optional<StorageRegistry.StoredLocation> locOpt = StorageRegistry.getForY(Role.OUTPUT, floorY);
        if (locOpt.isEmpty()) {
            // Try to find ANY floor with output storage as fallback
            Optional<Integer> anyFloorY = StorageRegistry.getAnyFloorWithStorage(Role.OUTPUT, floorY);
            if (anyFloorY.isPresent()) {
                int newFloorY = anyFloorY.get();
                dbg("no OUTPUT at Y=" + floorY + ", found at Y=" + newFloorY + " - updating primaryFloorY");
                primaryFloorY = newFloorY;
                floorY = newFloorY;
                locOpt = StorageRegistry.getForY(Role.OUTPUT, floorY);
            }
        }
        if (locOpt.isEmpty()) {
            int empty = InventoryOps.emptyMainSlots(client.player);
            if (empty <= DUMP_TRIGGER_EMPTY_SLOTS) {
                say(client, "no OUTPUT storage found, inventory full! Use: /traderun storage set output");
                stop();
            } else {
                dumpStartMs = 0L;
                state = State.SEEK;
            }
            return;
        }
        if (currentChestPos == null) {
            currentChestPos = locOpt.get().toBlockPos();
            // Get the saved open spot (where player stood when setting storage)
            Optional<Vec3d> openSpotOpt = StorageRegistry.getOpenSpot(Role.OUTPUT, floorY);
            if (openSpotOpt.isPresent()) {
                Vec3d spot = openSpotOpt.get();
                // Use floor() for accurate block position from player coordinates
                currentChestOpenSpot = new BlockPos(
                    (int) Math.floor(spot.x), 
                    (int) Math.floor(spot.y), 
                    (int) Math.floor(spot.z)
                );
                dbg("going to OUTPUT chest via saved spot " + currentChestOpenSpot.toShortString());
            } else {
                currentChestOpenSpot = null;
                dbg("going to OUTPUT chest at " + currentChestPos.toShortString() + " (no saved spot)");
            }
            statusThrottled(client, "→ Walking to output");
        }

        if (canInteractWithContainer(client, currentChestPos) || client.currentScreen != null) {
            navigator.stop();
            
            // Face the target chest before interacting
            faceBlock(client, currentChestPos);

            if (containerSession == null) {
                containerSession = new ContainerOps.Session(
                        ContainerOps.Mode.DEPOSIT_OUTPUT_ITEM,
                        0,
                        -1
                );

                StorageRegistry.getRememberedItem(Role.OUTPUT, floorY).ifPresent(id -> containerSession.outputItemId = id);
                if (containerSession.outputItemId == null && learnedOutputItemId != null) containerSession.outputItemId = learnedOutputItemId;

                containerOpenFirstAttemptMs = 0L;
                containerLastInteractMs = 0L;
            }

            tickOpenAndRunContainerSession(client, containerSession);

            if (containerSession.done) {
                if (containerSession.outputItemId != null) {
                    learnedOutputItemId = containerSession.outputItemId;
                    StorageRegistry.updateRememberedItem(Role.OUTPUT, floorY, containerSession.outputItemId);
                }
                
                // Check if we still have output items - means chest is full
                Identifier outId = containerSession.outputItemId;
                int remaining = (outId != null) ? countItemById(client, outId) : 0;
                if (remaining > 0) {
                    outputChestFull = true;
                    // Set cooldowns so we wait 20s before trying again
                    lastWaitCheckMs = System.currentTimeMillis();
                    nextDumpAllowedMs = System.currentTimeMillis() + WAIT_CHECK_INTERVAL_MS;
                    dbg("output chest full, " + remaining + " items remaining, waiting 20s");
                } else {
                    // Dump succeeded - reset full flag and wait mode
                    outputChestFull = false;
                    nextDumpAllowedMs = 0L;
                    if (waitReason != null && waitReason.equals("output")) {
                        waitReason = null;
                        waitStartMs = 0L;
                        savedChestPos = null;
                        say(client, "Output chest has space - resuming trading");
                    }
                    dbg("dump successful, chest has space");
                }

                closeAnyScreenProperly(client);
                containerSession = null;
                currentChestPos = null;
        currentChestOpenSpot = null;
                currentApproachGoal = null;
                approachKind = ApproachKind.VILLAGER;
                dumpStartMs = 0L;
                state = State.SEEK;
                dbg("dump done -> SEEK");
            }
            return;
        }

        if (currentApproachGoal == null) {
            approachKind = ApproachKind.OUTPUT_CHEST;
            // Use saved open spot if available, otherwise approach chest block
            BlockPos navTarget = (currentChestOpenSpot != null) ? currentChestOpenSpot : currentChestPos;
            currentApproachGoal = navigator.gotoStoragePosition(client, navTarget);
            resetApproachTracking(client);
            if (currentApproachGoal == null) {
                String err = navigator.getLastError();
                say(client, "⚠ Can't reach OUTPUT: " + (err != null ? err : "path blocked"));
                currentChestPos = null;
                currentChestOpenSpot = null;
                state = State.SEEK;
            }
            return;
        }

        double dGoal = distSqToGoal(client, currentApproachGoal);
        if (dGoal > APPROACH_GOAL_RANGE_SQ) {
            if (System.currentTimeMillis() - approachStartMs > STORAGE_NAV_TIMEOUT_MS) {
                navigator.stop();
                String dist = String.format("%.1f", Math.sqrt(dGoal));
                dbg("dump nav timeout: couldn't reach output chest in 12s, dist=" + dist);
                say(client, "⚠ Path blocked! Can't reach OUTPUT chest (dist=" + dist + ")");
                currentChestPos = null;
                currentChestOpenSpot = null;
                currentApproachGoal = null;
                state = State.SEEK;
                dbg("DETOUR_DUMP -> SEEK (nav timeout)");
            }
            return;
        }

        // We've reached the nav goal - check if we can interact
        if (!canInteractWithContainer(client, currentChestPos)) {
            // At nav goal but can't interact - try navigating directly to chest
            if (currentChestOpenSpot != null && !currentApproachGoal.equals(currentChestPos)) {
                dbg("at open spot but can't interact, trying direct chest approach");
                currentApproachGoal = navigator.gotoBlockApproach(client, currentChestPos);
                currentChestOpenSpot = null;  // Don't use open spot again
                resetApproachTracking(client);
                return;
            }
        }

        navigator.stop();
        state = State.DETOUR_DUMP;
    }
    
    /**
     * Return input items to input chest before switching floors.
     * After done, proceed with floor transition.
     */
    private void tickReturnInput(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        
        long now = System.currentTimeMillis();
        if (now < blockContainerInteractUntilMs) return;
        
        int floorY = currentFloorKeyY(client);
        
        // Make sure we know what item to return
        if (itemToReturn == null) {
            dbg("return input: no item to return, skipping to floor switch");
            proceedWithPendingFloorSwitch(client);
            return;
        }
        
        // Check how many we have left
        int remaining = countItemById(client, itemToReturn);
        if (remaining == 0) {
            dbg("return input: no items remaining, proceeding to floor switch");
            proceedWithPendingFloorSwitch(client);
            return;
        }
        
        // Get input chest location
        if (currentChestPos == null) {
            Optional<StorageRegistry.StoredLocation> locOpt = StorageRegistry.getForY(Role.INPUT, floorY);
            if (locOpt.isEmpty()) {
                dbg("return input: no INPUT storage for Y=" + floorY + ", skipping");
                proceedWithPendingFloorSwitch(client);
                return;
            }
            currentChestPos = locOpt.get().toBlockPos();
            Optional<Vec3d> openSpotOpt = StorageRegistry.getOpenSpot(Role.INPUT, floorY);
            if (openSpotOpt.isPresent()) {
                Vec3d spot = openSpotOpt.get();
                // Use floor() for accurate block position from player coordinates
                currentChestOpenSpot = new BlockPos(
                    (int) Math.floor(spot.x), 
                    (int) Math.floor(spot.y), 
                    (int) Math.floor(spot.z)
                );
                dbg("return input: going to INPUT chest via saved spot " + currentChestOpenSpot.toShortString());
            } else {
                currentChestOpenSpot = null;
            }
            statusThrottled(client, "📦 Returning items to input");
        }
        
        // If we can interact with the chest, do so
        if (canInteractWithContainer(client, currentChestPos) || client.currentScreen != null) {
            navigator.stop();
            
            // Face the target chest before interacting
            faceBlock(client, currentChestPos);
            
            if (containerSession == null) {
                containerSession = new ContainerOps.Session(
                    ContainerOps.Mode.DEPOSIT_INPUT_ITEM,
                    0,
                    -1
                );
                containerSession.inputItemId = itemToReturn;
                containerOpenFirstAttemptMs = 0L;
                containerLastInteractMs = 0L;
            }
            
            tickOpenAndRunContainerSession(client, containerSession);
            
            if (containerSession.done) {
                closeAnyScreenProperly(client);
                containerSession = null;
                currentChestPos = null;
                currentChestOpenSpot = null;
                currentApproachGoal = null;
                
                // Check if we still have items (chest might be full)
                int stillHave = countItemById(client, itemToReturn);
                if (stillHave > 0) {
                    dbg("return input: could only deposit some, " + stillHave + " remaining");
                }
                
                // Proceed with floor switch
                proceedWithPendingFloorSwitch(client);
            }
            return;
        }
        
        // Navigate to chest
        if (currentApproachGoal == null) {
            approachKind = ApproachKind.INPUT_CHEST;
            BlockPos navTarget = (currentChestOpenSpot != null) ? currentChestOpenSpot : currentChestPos;
            currentApproachGoal = navigator.gotoStoragePosition(client, navTarget);
            resetApproachTracking(client);
            if (currentApproachGoal == null) {
                String err = navigator.getLastError();
                dbg("return input: can't navigate - " + (err != null ? err : "path blocked"));
                currentChestPos = null;
                currentChestOpenSpot = null;
                proceedWithPendingFloorSwitch(client);
            }
            return;
        }
        
        // Check if we reached the goal
        double dGoal = distSqToGoal(client, currentApproachGoal);
        if (dGoal > APPROACH_GOAL_RANGE_SQ) {
            // Still navigating
            if (System.currentTimeMillis() - approachStartMs > APPROACH_TIMEOUT_MS) {
                dbg("return input: navigation timeout - cancelling floor switch, staying on current floor");
                navigator.stop();
                currentChestPos = null;
                currentChestOpenSpot = null;
                currentApproachGoal = null;
                // Cancel the pending floor switch - don't switch with full inventory
                pendingFloorSwitchName = null;
                pendingFloorSwitchIndex = 0;
                itemToReturn = null;
                say(client, "Couldn't reach input chest - staying on current floor");
                state = State.SEEK;
            }
            return;
        }
        
        // At nav goal but can't interact - keep trying or give up
        navigator.stop();
    }
    
    /**
     * Complete the pending floor switch after returning items.
     */
    private void proceedWithPendingFloorSwitch(MinecraftClient client) {
        if (pendingFloorSwitchName == null) {
            dbg("proceedWithPendingFloorSwitch: no pending switch, going to SEEK");
            state = State.SEEK;
            return;
        }
        
        Optional<FloorRegistry.FloorInfo> floorOpt = FloorRegistry.getFloorByName(pendingFloorSwitchName);
        if (floorOpt.isEmpty()) {
            dbg("proceedWithPendingFloorSwitch: floor not found, going to SEEK");
            pendingFloorSwitchName = null;
            state = State.SEEK;
            return;
        }
        
        FloorRegistry.FloorInfo floor = floorOpt.get();
        
        currentFloorIndex = pendingFloorSwitchIndex;
        primaryFloorY = floor.y;
        targetFloorY = floor.y;
        transitionPoint = new BlockPos(floor.clusterX, floor.y, floor.clusterZ);
        floorTransitionPhase = 0;
        floorTransitionStartY = client != null && client.player != null ? client.player.getBlockPos().getY() : floor.y;
        floorTransitionStartMs = System.currentTimeMillis();
        if (floorTransitionStartY == targetFloorY) {
            dbg("proceedWithPendingFloorSwitch: already at Y=" + targetFloorY + ", skipping transition");
            pendingFloorSwitchName = null;
            pendingFloorSwitchIndex = 0;
            itemToReturn = null;
            state = State.SEEK;
            return;
        }
        state = State.FLOOR_TRANSITION;
        
        // Update target professions to ONLY the new floor's professions
        villagerFinder.setTargetProfessions(new ArrayList<>(floor.professions));
        villagerFinder.setTargetFloorY(floor.y);
        // Clear session-learned items to prevent cross-floor contamination
        learnedInputItemId = null;
        learnedOutputItemId = null;
        dbg("Switching to floor: " + pendingFloorSwitchName + " Y=" + floor.y + " profs=" + floor.professions);
        say(client, "Switching to floor: " + pendingFloorSwitchName);
        
        // Clear pending switch info
        pendingFloorSwitchName = null;
        pendingFloorSwitchIndex = 0;
        itemToReturn = null;
    }

    private void tickOpenAndRunContainerSession(MinecraftClient client, ContainerOps.Session session) {
        long now = System.currentTimeMillis();
        if (containerOpenFirstAttemptMs == 0L) {
            containerOpenFirstAttemptMs = now;
        }

        if (client.currentScreen == null) {
            if (now - containerOpenFirstAttemptMs > 3000L) {
                session.error = "Failed to open container";
                session.done = true;
                dbg("container open timeout");
                return;
            }

            long baseDelay = 220L;
            long jitter = rng.nextInt(40);
            long delay = baseDelay + jitter;

            if (now - containerLastInteractMs >= delay) {
                lookAtBlock(client, currentChestPos);
                BlockHitResult bhr = new BlockHitResult(
                        new Vec3d(currentChestPos.getX() + 0.5, currentChestPos.getY() + 0.5, currentChestPos.getZ() + 0.5),
                        Direction.UP,
                        currentChestPos,
                        false
                );
                client.interactionManager.interactBlock(client.player, net.minecraft.util.Hand.MAIN_HAND, bhr);
                containerLastInteractMs = now;
            }
            return;
        }

        // Screen is open, tick the session
        session.tick(client);
        
        // Debug: show session state
        if (session.done) {
            if (session.error != null) {
                dbg("container session error: " + session.error);
                say(client, session.error);
            } else {
                dbg("container session completed OK");
            }
        } else {
            // Safety timeout: if session running too long (10s), force close
            if (now - containerOpenFirstAttemptMs > 10000L) {
                session.error = "Container session timeout";
                session.done = true;
                dbg("container session timeout forced");
                say(client, "Container session timeout - closing");
            }
        }
    }

    private long nextSeekAllowedMs = 0L; // Delay between seek attempts after failures
    
    // Floor transition state
    private int targetFloorY = 0;
    private BlockPos transitionPoint = null;
    private int floorTransitionPhase = 0; // 0=approach transition, 1=traverse stairs, 2=done
    private long floorTransitionStartMs = 0L;
    private int floorTransitionStartY = 0;
    
    private void tickSeek(MinecraftClient client) {
        if (client.world == null || client.player == null) return;
        
        // If in wait mode, let the player interact freely (don't close their screens)
        if (waitReason != null && client.currentScreen != null) {
            return;  // Player is doing something, don't interfere
        }
        
        // If a screen is open (leftover from dump/restock), close it - but only if not in wait mode
        if (client.currentScreen != null && waitReason == null) {
            closeAnyScreenProperly(client);
            return;
        }

        long now = System.currentTimeMillis();
        
        // Rate limit SEEK to prevent rapid cycling after failures
        if (now < nextSeekAllowedMs) {
            return;
        }
        
        int playerY = client.player.getBlockPos().getY();
        int floorY = currentFloorKeyY(client);

        // If we accidentally fell off the current active floor, climb back up before seeking/switching
        if (ensureOnActiveFloor(client, playerY)) {
            return;
        }

        // In multi-floor mode, check if we should switch floors BEFORE restocking
        // This prevents collecting the wrong floor's input items
        if (!activeFloorNames.isEmpty() && activeFloorNames.size() > 1) {
            // Check if current floor is exhausted (no villagers or all on cooldown)
            Optional<VillagerEntity> currentFloorVillager = villagerFinder.findBestTarget(client);
            if (currentFloorVillager.isEmpty()) {
                // Try to switch floors first - don't restock from wrong floor
                if (tryNextFloor(client)) {
                    dbg("multi-floor: switching before restock");
                    return;
                }
                // If we can't switch floors, continue to normal restock logic below
            }
        }

        // Get input item for THIS floor specifically - don't use learnedInputItemId as fallback
        // in multi-floor mode because it might be from a different floor
        Identifier inputId = StorageRegistry.getRememberedItem(Role.INPUT, floorY).orElse(null);
        if (inputId == null && activeFloorNames.size() <= 1) {
            // Single floor mode: can use session-learned item as fallback
            inputId = learnedInputItemId;
        }
        int haveInput = (inputId == null) ? 0 : countItemById(client, inputId);
        int inputMin = TradeRunSettings.get().inputMin;
        
        // Always need at least 1 input item to trade, regardless of inputMin setting
        int effectiveMin = Math.max(1, inputMin);

        // Check if we need items (must have at least 1 to trade!)
        // In multi-floor mode with no remembered input, ALWAYS restock to learn the input item
        boolean needsRestock = (inputId == null || haveInput < effectiveMin);
        
        // ALWAYS log the input check for debugging
        dbg("SEEK input check: inputId=" + (inputId != null ? inputId.getPath() : "null") + 
            " have=" + haveInput + "/" + effectiveMin + " needsRestock=" + needsRestock + " floorY=" + floorY);
        
        if (needsRestock) {
            // If input chest was empty, try other floors first (in multi-floor mode)
            if (inputChestEmpty) {
                // In multi-floor mode, try switching to another floor instead of waiting
                if (!activeFloorNames.isEmpty() && activeFloorNames.size() > 1) {
                    dbg("input empty on this floor, trying other floors");
                    if (tryNextFloor(client)) {
                        if (handleInputEmptyFloorSwitch(client)) {
                            return;
                        }
                        // Reset input empty flag since we're leaving this floor
                        inputChestEmpty = false;
                        say(client, "Input empty - trying next floor");
                        return;
                    }
                    // All floors checked, fall through to wait mode
                }
                
                // Check if cooldown has passed
                if (now < nextRestockCheckMs) {
                    // Show wait status in hotbar
                    long secsLeft = (nextRestockCheckMs - now) / 1000;
                    statusThrottled(client, "⏳ Waiting for input (" + secsLeft + "s)");
                    
                    // Still in cooldown - show countdown message every 5 seconds
                    if (now - lastWaitMessageMs >= WAIT_MESSAGE_INTERVAL_MS) {
                        lastWaitMessageMs = now;
                        long elapsed = now - waitStartMs;
                        long remaining = WAIT_TIMEOUT_MS - elapsed;
                        
                        if (remaining <= 0) {
                            say(client, "Auto-stopping after 10 minutes of waiting for input");
                            stop();
                            return;
                        }
                        
                        int mins = (int) (remaining / 60000);
                        int secs = (int) ((remaining % 60000) / 1000);
                        say(client, "no input detected, auto stop in " + mins + ":" + String.format("%02d", secs));
                    }
                    return;
                }
                
                // Cooldown passed - go check the chest
                dbg("wait check: navigating to input chest");
                currentChestPos = savedChestPos;
                currentApproachGoal = null;
                containerSession = null;
                state = State.DETOUR_RESTOCK;
                return;
            }
            
            // Not in wait mode yet - go check the chest
            currentApproachGoal = null;
            currentChestPos = null;
            currentChestOpenSpot = null;
            containerSession = null;
            state = State.DETOUR_RESTOCK;
            statusThrottled(client, "📦 Need input items");
            dbg("SEEK -> DETOUR_RESTOCK (have " + haveInput + "/" + effectiveMin + " input items)");
            return;
        }
        
        // We have items, reset wait timer and mode
        nextRestockAllowedMs = 0L;
        if (inputChestEmpty || (waitReason != null && waitReason.equals("input"))) {
            inputChestEmpty = false;
            nextRestockCheckMs = 0L;
            waitReason = null;
            waitStartMs = 0L;
            savedChestPos = null;
            emptyInputFloorSwitches = 0;
            emptyInputRotationCount = 0;
            emptyInputWaitCycles = 0;
            say(client, "Input items available - resuming trading");
        }

        // Get output item for THIS floor specifically
        Identifier outId = StorageRegistry.getRememberedItem(Role.OUTPUT, floorY).orElse(null);
        if (outId == null && activeFloorNames.size() <= 1) {
            outId = learnedOutputItemId;
        }
        int haveOut = (outId == null) ? 0 : countItemById(client, outId);
        int outputMin = TradeRunSettings.get().outputMin;

        int empty = InventoryOps.emptyMainSlots(client.player);
        boolean invFull = empty <= DUMP_TRIGGER_EMPTY_SLOTS;
        boolean thresholdHit = (outputMin > 0) && (haveOut >= outputMin);

        // If output chest is full, enter waiting mode instead of trying to dump
        if (outputChestFull && invFull && haveOut > 0) {
            long timeSinceLastCheck = now - lastWaitCheckMs;
            // Enter waiting mode - check output chest periodically
            if (waitReason == null || !waitReason.equals("output")) {
                waitStartMs = now;
                waitReason = "output";
                lastWaitCheckMs = now; // Start counting from now
                lastWaitMessageMs = 0L;
                // Save the output chest position for navigating back
                int floorYForChest = currentFloorKeyY(client);
                StorageRegistry.getForY(Role.OUTPUT, floorYForChest).ifPresent(loc -> {
                    savedChestPos = loc.toBlockPos();
                });
                say(client, "Output chest full - waiting mode (you can move freely)");
            }
            
            // Check if timeout reached
            long elapsed = now - waitStartMs;
            if (elapsed >= WAIT_TIMEOUT_MS) {
                say(client, "Auto-stopping after 10 minutes of waiting");
                stop();
                return;
            }
            
            // Show countdown message every 5 seconds
            if (now - lastWaitMessageMs >= WAIT_MESSAGE_INTERVAL_MS) {
                lastWaitMessageMs = now;
                long remaining = WAIT_TIMEOUT_MS - elapsed;
                int mins = (int) (remaining / 60000);
                int secs = (int) ((remaining % 60000) / 1000);
                say(client, "no output space, auto stop in " + mins + ":" + String.format("%02d", secs));
            }
            
            // Check output chest every 20 seconds - navigate back and try
            if (now - lastWaitCheckMs >= WAIT_CHECK_INTERVAL_MS) {
                lastWaitCheckMs = now;
                // Don't reset outputChestFull here - only reset when dump actually works
                currentChestPos = savedChestPos;
                currentApproachGoal = null;
                containerSession = null;
                state = State.DETOUR_DUMP;
                dbg("wait check: navigating to output chest");
            }
            return;
        }
        
        // Reset wait mode if we have inventory space now
        if (waitReason != null && waitReason.equals("output") && !invFull) {
            waitReason = null;
            waitStartMs = 0L;
            savedChestPos = null;
            outputChestFull = false;
        }
        
        // Normal dump check - when outputMin threshold is reached OR inventory is completely full
        boolean invCompletelyFull = (empty == 0);
        boolean shouldDump = thresholdHit || (invCompletelyFull && haveOut > 0);
        
        // If inventory is full but we don't know output item, warn user
        if (invCompletelyFull && outId == null) {
            say(client, "§c⚠ Inventory full! Open OUTPUT chest once to teach me the output item");
            stop();
            return;
        }
        
        if (!outputChestFull && shouldDump) {
            currentApproachGoal = null;
            currentChestPos = null;
            currentChestOpenSpot = null;
            containerSession = null;
            state = State.DETOUR_DUMP;
            dbg("SEEK -> DETOUR_DUMP (threshold=" + thresholdHit + " invFull=" + invCompletelyFull + ")");
            return;
        }

        Optional<VillagerEntity> best = villagerFinder.findBestTarget(client);
        if (best.isEmpty()) {
            // Always clear fail registry and retry when no villager found
            RecentFailRegistry.clearAll();
            best = villagerFinder.findBestTarget(client);
            
            if (best.isEmpty()) {
                dbg("SEEK: no villager - " + villagerFinder.debugCounts(client));
            } else {
                dbg("SEEK: found villager after clearing fails at " + best.get().getBlockPos().toShortString());
            }
        } else {
            dbg("SEEK: found villager at " + best.get().getBlockPos().toShortString());
        }
        
        // If no villagers on current floor, check if we should change floors or restock
        if (best.isEmpty()) {
            int totalVillagers = villagerFinder.countAllVillagersOnFloor(client);
            boolean hasAvailableVillager = villagerFinder.hasVillagerWithoutCooldown(client);
            boolean allOnCooldown = (totalVillagers > 0) && !hasAvailableVillager;
            
            if (totalVillagers > 0 && hasAvailableVillager) {
                dbg("SEEK: accessible villagers remain on this floor; waiting before switching");
                nextSeekAllowedMs = now + 400L;
                return;
            }

            if (allOnCooldown) {
                // If using floor names mode with multiple floors, try switching floors first
                if (!activeFloorNames.isEmpty() && activeFloorNames.size() > 1) {
                    if (tryNextFloor(client)) {
                        dbg("all on cooldown - switching to next named floor");
                        return;
                    }
                }
                
                // All villagers on cooldown - use this time to restock if needed
                // Reuse existing inputId and haveInput from above
                int inputMax = 64 * 27; // Roughly a full inventory
                
                // If we have room for more input items, go restock
                if (haveInput < inputMax && InventoryOps.emptyMainSlots(client.player) > 3) {
                    Optional<StorageRegistry.StoredLocation> inputLoc = StorageRegistry.getForY(Role.INPUT, floorY);
                    if (inputLoc.isPresent()) {
                        dbg("all villagers on cooldown, restocking while waiting");
                        currentApproachGoal = null;
                        currentChestPos = null;
        currentChestOpenSpot = null;
                        containerSession = null;
                        state = State.DETOUR_RESTOCK;
                        return;
                    }
                }
            }
            
            // Only change floors if multi-profession mode and another floor has 2+ villagers
            if (totalVillagers == 0 || allOnCooldown) {
                Optional<Integer> targetFloor = villagerFinder.findBestFloorToMoveTo(client);
                if (targetFloor.isPresent()) {
                    int newY = targetFloor.get();
                    int currentY = client.player.getBlockPos().getY();
                    
                    // Find a walkable destination on the target floor - prefer storage locations
                    BlockPos destination = null;
                    Optional<StorageRegistry.StoredLocation> inputStorage = StorageRegistry.getForY(Role.INPUT, newY);
                    if (inputStorage.isPresent()) {
                        StorageRegistry.StoredLocation storage = inputStorage.get();
                        destination = new BlockPos(storage.x, newY, storage.z);
                    } else {
                        Optional<StorageRegistry.StoredLocation> outputStorage = StorageRegistry.getForY(Role.OUTPUT, newY);
                        if (outputStorage.isPresent()) {
                            StorageRegistry.StoredLocation storage = outputStorage.get();
                            destination = new BlockPos(storage.x, newY, storage.z);
                        }
                    }
                    
                    if (destination != null) {
                        say(client, "Floor exhausted. Moving to Y=" + newY);
                        dbg("SEEK -> FLOOR_TRANSITION to Y=" + newY);
                        
                        // Set up floor transition
                        targetFloorY = newY;
                        transitionPoint = destination;
                        floorTransitionPhase = 0;
                        floorTransitionStartY = currentY;
                        floorTransitionStartMs = System.currentTimeMillis();
                        state = State.FLOOR_TRANSITION;
                        return;
                    } else {
                        say(client, "Floor exhausted. Y=" + newY + " has villagers but no storage set.");
                        say(client, "Set storage: /traderun storage set input (look at chest)");
                    }
                }
            }
            return;
        }

        currentTarget = best.get();

        resetOpenAttemptState();
        attemptedDiagonalFromHang = false;
        usingDiagonal = false;

        nudgeTimes = 0;
        nudgeTicksRemaining = 0;
        nudgeStartPos = null;

        // Use smart approach - prefers cardinal directions, only uses diagonals if necessary
        BlockPos goal = navigator.gotoVillagerApproachPoint(client, currentTarget);
        
        // Debug approach selection
        boolean isDiag = navigator.wasLastApproachDiagonal();
        dbg("approach: " + (goal != null ? goal.toShortString() : "null") + " diagonal=" + isDiag);

        currentApproachGoal = goal;
        approachKind = ApproachKind.VILLAGER;

        if (goal == null) {
            // Use longer cooldown for "no approach" failures - environment won't change quickly
            RecentFailRegistry.markNoApproachFailure(currentTarget);
            currentTarget = null;
            nextSeekAllowedMs = now + 500L; // Small delay before trying next
            state = State.SEEK;
            dbg("SEEK: no valid approach for villager (60s cooldown)");
            return;
        }

        usingDiagonal = isDiagonalApproach(currentTarget, goal);
        resetApproachTracking(client);
        state = State.APPROACH;
        statusThrottled(client, "→ Walking to villager");
        dbg("SEEK -> APPROACH villager (diagonal=" + usingDiagonal + ")");
    }

    /**
     * Track how many times we've rotated through floors while inputs are empty.
     * Stops the run after six full rotations without finding any items.
     *
     * @return true if the run was stopped and caller should return immediately
     */
    private boolean handleInputEmptyFloorSwitch(MinecraftClient client) {
        if (activeFloorNames.isEmpty() || activeFloorNames.size() <= 1) {
            return false;
        }

        // Multi-floor mode - single-floor wait counter no longer relevant
        emptyInputWaitCycles = 0;

        emptyInputFloorSwitches++;
        dbg("input empty: switched floors (" + emptyInputFloorSwitches + "/" + activeFloorNames.size() + " this rotation)");

        if (emptyInputFloorSwitches >= activeFloorNames.size()) {
            emptyInputFloorSwitches = 0;
            emptyInputRotationCount++;
            dbg("input empty: completed rotation " + emptyInputRotationCount + "/" + MAX_INPUT_FLOOR_ROTATIONS);

            if (emptyInputRotationCount >= MAX_INPUT_FLOOR_ROTATIONS) {
                String msg = "FAIL: no input after checking floors " + MAX_INPUT_FLOOR_ROTATIONS + " times";
                DebugLogger.error(msg);
                say(client, msg);
                stop();
                return true;
            }
        }

        return false;
    }

    /**
     * If the player falls off the currently active floor, force a quick transition
     * back to that floor instead of switching to a different profession floor.
     *
     * @return true if we started a recovery transition.
     */
    private boolean shouldEnforceFloorLock() {
        return state == State.SEEK ||
               state == State.APPROACH ||
               state == State.OPEN_ATTEMPTS ||
               state == State.NUDGE_FORWARD ||
               state == State.WAIT_CLOSE;
    }

    private boolean ensureOnActiveFloor(MinecraftClient client, int playerY) {
        if (client == null || client.player == null) return false;
        if (!shouldEnforceFloorLock()) return false;
        if (activeFloorNames.isEmpty() || currentFloorIndex >= activeFloorNames.size()) return false;

        String currentFloorName = activeFloorNames.get(currentFloorIndex);
        Optional<FloorRegistry.FloorInfo> floorOpt = FloorRegistry.getFloorByName(currentFloorName);
        if (floorOpt.isEmpty()) return false;

        FloorRegistry.FloorInfo floor = floorOpt.get();
        int floorY = floor.y;

        // Allow small tolerance (1 block drop) before forcing a return
        if (playerY >= floorY - 1) {
            return false; // Still close enough to floor height
        }

        // Navigate back to the floor - use storage location as a known walkable destination
        navigator.stop();
        releaseUseKey(client);
        releaseForwardKey(client);
        targetFloorY = floorY;
        floorTransitionStartY = playerY;
        
        // Find a walkable destination on the target floor - prefer input storage, then output
        BlockPos destination = null;
        Optional<StorageRegistry.StoredLocation> inputStorage = StorageRegistry.getForY(Role.INPUT, floorY);
        if (inputStorage.isPresent()) {
            StorageRegistry.StoredLocation storage = inputStorage.get();
            destination = new BlockPos(storage.x, floorY, storage.z);
        } else {
            Optional<StorageRegistry.StoredLocation> outputStorage = StorageRegistry.getForY(Role.OUTPUT, floorY);
            if (outputStorage.isPresent()) {
                StorageRegistry.StoredLocation storage = outputStorage.get();
                destination = new BlockPos(storage.x, floorY, storage.z);
            } else {
                // Fallback to cluster center if no storage
                destination = new BlockPos(floor.clusterX, floorY, floor.clusterZ);
            }
        }
        
        transitionPoint = destination;
        floorTransitionPhase = 0;
        floorTransitionStartMs = System.currentTimeMillis();
        state = State.FLOOR_TRANSITION;
        say(client, "Dropped off " + currentFloorName + " floor - heading back to Y=" + floorY);
        dbg("ensureOnActiveFloor: playerY=" + playerY + ", floorY=" + floorY + " -> returning to " + destination.toShortString());
        return true;
    }

    /**
     * Handle repeated empty chest checks when only one floor is active.
     * Cancels the run after MAX_SINGLE_FLOOR_EMPTY_CYCLES attempts.
     *
     * @return true if the run was stopped.
     */
    private boolean handleSingleFloorInputEmpty(MinecraftClient client) {
        if (!activeFloorNames.isEmpty() && activeFloorNames.size() > 1) {
            return false;  // Multi-floor mode handled elsewhere
        }

        emptyInputWaitCycles++;
        dbg("input empty: single-floor wait cycle " + emptyInputWaitCycles + "/" + MAX_SINGLE_FLOOR_EMPTY_CYCLES);

        if (emptyInputWaitCycles >= MAX_SINGLE_FLOOR_EMPTY_CYCLES) {
            String msg = "FAIL: no input after checking this floor " + MAX_SINGLE_FLOOR_EMPTY_CYCLES + " times";
            DebugLogger.error(msg);
            say(client, msg);
            stop();
            return true;
        }
        return false;
    }

    private void tickApproach(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        long now = System.currentTimeMillis();
        if (approachStartMs == 0L) resetApproachTracking(client);

        // Check if navigator had any error - fail this villager and move on
        String navError = navigator.getLastError();
        if (navError != null) {
            navigator.stop();
            navigator.clearLastError();
            if (approachKind == ApproachKind.VILLAGER && currentTarget != null) {
                boolean secondFail = RecentFailRegistry.markDiagonalFailure(currentTarget);
                String cooldown = secondFail ? "15s" : "5s";
                dbg("nav error: " + navError + ", skipping villager (" + cooldown + ")");
                say(client, "⚠ Path blocked - trying other villagers");
            } else if (approachKind == ApproachKind.INPUT_CHEST) {
                say(client, "⚠ Can't reach INPUT chest: " + navError);
            } else if (approachKind == ApproachKind.OUTPUT_CHEST) {
                say(client, "⚠ Can't reach OUTPUT chest: " + navError);
            }
            currentTarget = null;
            currentApproachGoal = null;
            // Small delay before seeking next villager
            nextSeekAllowedMs = System.currentTimeMillis() + 300L;
            state = State.SEEK;
            return;
        }

        if (approachKind == ApproachKind.VILLAGER) {
            if (currentTarget == null || !currentTarget.isAlive()) {
                navigator.stop();
                currentTarget = null;
                currentApproachGoal = null;
                state = State.SEEK;
                return;
            }
        }

        if (currentApproachGoal == null) {
            state = State.SEEK;
            return;
        }

        double distGoalSq = distSqToGoal(client, currentApproachGoal);

        if (distGoalSq <= APPROACH_GOAL_RANGE_SQ) {
            navigator.stop();
            if (approachKind == ApproachKind.VILLAGER) {
                resetOpenAttemptState();
                state = State.OPEN_ATTEMPTS;
                statusThrottled(client, "⚡ Opening trade");
                dbg("APPROACH -> OPEN_ATTEMPTS");
            } else if (approachKind == ApproachKind.INPUT_CHEST) {
                statusThrottled(client, "📦 At input chest");
                state = State.DETOUR_RESTOCK;
            } else {
                statusThrottled(client, "📦 At output chest");
                state = State.DETOUR_DUMP;
            }
            return;
        }

        // If approach is stuck AND not at goal, try an alternate position
        if (approachKind == ApproachKind.VILLAGER && !attemptedDiagonalFromHang && distGoalSq > APPROACH_GOAL_RANGE_SQ) {
            long elapsed = now - approachStartMs;
            if (elapsed >= STRAIGHT_APPROACH_HANG_MS && approachStartPlayerPos != null) {
                Vec3d curPos = client.player.getPos();
                double movedSq = horizDistSq(curPos, approachStartPlayerPos);
                double improve = approachStartGoalDistSq - distGoalSq;

                if (movedSq < HANG_MIN_PROGRESS_DISTSQ && improve < HANG_MIN_IMPROVE_DISTSQ) {
                    attemptedDiagonalFromHang = true;
                    dbg("approach hung, trying alternate position");
                    BlockPos altGoal = navigator.gotoVillagerApproachPointAlternate(client, currentTarget, currentApproachGoal);
                    if (altGoal != null) {
                        currentApproachGoal = altGoal;
                        usingDiagonal = isDiagonalApproach(currentTarget, altGoal);
                        resetApproachTracking(client);
                        dbg("switched to alternate (diagonal=" + usingDiagonal + ")");
                        return;
                    }
                }
            }
        }
        
        // Rolling stuck detection - check every 1.5s if we're making any progress
        if (approachKind == ApproachKind.VILLAGER && now - lastStuckCheckMs >= STUCK_CHECK_INTERVAL_MS) {
            Vec3d curPos = client.player.getPos();
            if (lastStuckCheckPos != null) {
                double movedSq = horizDistSq(curPos, lastStuckCheckPos);
                if (movedSq < STUCK_MIN_MOVEMENT_SQ) {
                    stuckCheckFailCount++;
                    dbg("stuck check failed (" + stuckCheckFailCount + "/" + STUCK_FAIL_THRESHOLD + "), moved only " + String.format("%.3f", Math.sqrt(movedSq)) + " blocks");
                    if (stuckCheckFailCount >= STUCK_FAIL_THRESHOLD) {
                        // Truly stuck - fail this villager quickly
                        navigator.stop();
                        if (currentTarget != null) {
                            RecentFailRegistry.markDiagonalFailure(currentTarget);
                            say(client, "⚠ Stuck - skipping villager");
                        }
                        currentTarget = null;
                        currentApproachGoal = null;
                        nextSeekAllowedMs = now + 300L;
                        state = State.SEEK;
                        dbg("stuck detection -> SEEK (failed " + stuckCheckFailCount + " checks)");
                        return;
                    }
                } else {
                    // Making progress, reset counter
                    stuckCheckFailCount = 0;
                }
            }
            lastStuckCheckMs = now;
            lastStuckCheckPos = curPos;
        }

        if (now - approachStartMs > APPROACH_TIMEOUT_MS) {
            navigator.stop();
            if (approachKind == ApproachKind.VILLAGER && currentTarget != null) {
                RecentFailRegistry.markFailure(currentTarget);
            }
            currentTarget = null;
            currentApproachGoal = null;
            state = State.SEEK;
            dbg("APPROACH timeout -> SEEK");
        }
    }

    private void tickOpenAttempts(MinecraftClient client) {
        if (client.player == null || client.world == null) return;

        if (currentTarget == null || !currentTarget.isAlive()) {
            releaseUseKey(client);
            state = State.SEEK;
            return;
        }

        int floorY = currentFloorKeyY(client);
        Identifier safeInputId = StorageRegistry.getRememberedItem(Role.INPUT, floorY).orElse(learnedInputItemId);
        if (!InventoryOps.ensureFreeHand(client.player, safeInputId)) {
            dbg("OPEN_ATTEMPTS: couldn't secure safe hand (floorY=" + floorY + ")");
        }

        long now = System.currentTimeMillis();
        if (firstOpenAttemptMs == 0L) firstOpenAttemptMs = now;

        if (client.currentScreen instanceof MerchantScreen) {
            releaseUseKey(client);
            state = State.WAIT_CLOSE;
            dbg("OPEN_ATTEMPTS -> WAIT_CLOSE (merchant open)");
            return;
        }

        long elapsed = now - firstOpenAttemptMs;

        double distSq = client.player.squaredDistanceTo(currentTarget);
        if (distSq > INTERACT_RANGE_VILLAGER_SQ) {
            releaseUseKey(client);
            RecentFailRegistry.markFailure(currentTarget);
            state = State.SEEK;
            return;
        }

        if (elapsed > OPEN_TIMEOUT_MS) {
            releaseUseKey(client);
            RecentFailRegistry.markFailure(currentTarget);
            state = State.SEEK;
            return;
        }

        if (usingDiagonal && elapsed >= DIAGONAL_RETRY_MS) {
            if (nudgeTimes < NUDGE_FORWARD_MAX_TIMES) {
                releaseUseKey(client);
                faceEntity(client, currentTarget);

                nudgeTimes++;
                nudgeTicksRemaining = NUDGE_FORWARD_MAX_TICKS;
                nudgeStartPos = client.player.getPos();
                state = State.NUDGE_FORWARD;
                dbg("OPEN_ATTEMPTS -> NUDGE_FORWARD");
                return;
            } else {
                // Diagonal approach failed after nudge - use retry logic
                releaseUseKey(client);
                boolean secondFail = RecentFailRegistry.markDiagonalFailure(currentTarget);
                if (secondFail) {
                    dbg("diagonal failed twice, skipping villager (15s)");
                } else {
                    dbg("diagonal failed, will retry after other villagers (5s)");
                }
                currentTarget = null;
                state = State.SEEK;
                return;
            }
        }

        if (now - lastAimMs >= AIM_DELAY_MS) {
            faceEntity(client, currentTarget);
            lastAimMs = now;
        }

        spamUseKey(client, now);
    }

    private void tickNudgeForward(MinecraftClient client) {
        if (client.player == null) return;

        if (client.currentScreen instanceof MerchantScreen) {
            releaseForwardKey(client);
            state = State.WAIT_CLOSE;
            return;
        }

        if (currentTarget == null || !currentTarget.isAlive()) {
            releaseForwardKey(client);
            state = State.SEEK;
            return;
        }

        if (nudgeStartPos == null) nudgeStartPos = client.player.getPos();

        pressForwardKey(client, true);

        Vec3d nowPos = client.player.getPos();
        double movedSq = horizDistSq(nowPos, nudgeStartPos);
        nudgeTicksRemaining--;

        if (movedSq >= NUDGE_FORWARD_DISTANCE_SQ || nudgeTicksRemaining <= 0) {
            pressForwardKey(client, false);
            resetOpenAttemptState();
            state = State.OPEN_ATTEMPTS;
        }
    }

    // Trade completion detection
    private int invItemCountAtTradeStart = 0;
    private long tradeItemReceivedMs = 0L;
    private long tradeGuiOpenedMs = 0L;
    private static final long CLOSE_DELAY_AFTER_TRADE_MS = 200L;
    private static final long NO_TRADE_TIMEOUT_MS = 300L; // Close after 0.3s if no trade
    
    private void tickWaitClose(MinecraftClient client) {
        if (client.player == null) return;
        
        // If screen is still open, check if we received trade items and should close
        if (client.currentScreen instanceof MerchantScreen) {
            long now = System.currentTimeMillis();
            int currentItemCount = countTotalInventoryItems(client);
            
            // First tick in WAIT_CLOSE - record starting inventory and time
            if (invItemCountAtTradeStart == 0) {
                invItemCountAtTradeStart = currentItemCount;
                tradeItemReceivedMs = 0L;
                tradeGuiOpenedMs = now;
                return;
            }
            
            // Check if we received new items
            if (currentItemCount > invItemCountAtTradeStart) {
                if (tradeItemReceivedMs == 0L) {
                    tradeItemReceivedMs = now;
                    dbg("trade items received, closing in 200ms");
                }
            }
            
            // Close after receiving items + delay
            if (tradeItemReceivedMs > 0L && now - tradeItemReceivedMs >= CLOSE_DELAY_AFTER_TRADE_MS) {
                closeAnyScreenProperly(client);
                // Don't return - fall through to cleanup below
            }
            // OR close after 1 second timeout with no trade
            else if (tradeItemReceivedMs == 0L && now - tradeGuiOpenedMs >= NO_TRADE_TIMEOUT_MS) {
                dbg("no trade after 1s, closing and marking as traded");
                closeAnyScreenProperly(client);
                // Don't return - fall through to cleanup below
            }
            else {
                return; // Still waiting
            }
        }
        
        // Screen is closed - cleanup and register cooldown
        if (currentTarget != null && !cooldownRegistered) {
            CooldownRegistry.onVillagerTraded(currentTarget);
            InteractedVillagerRegistry.markInteracted(currentTarget);
            RestockWatcher.markJustTraded(currentTarget); // Ignore particles for a few seconds
            cooldownRegistered = true;
            dbg("cooldown registered for villager");
            
            // After successful trade, clear short-term fails and retry counters
            // (player position changed, previously-blocked approaches might now be accessible)
            RecentFailRegistry.clearAll();
            RecentFailRegistry.clearFailCounts();
        }

        // STOP all navigation - prevents walking into villager after trade!
        navigator.stop();
        navigator.clearLastError();
        releaseForwardKey(client);

        // Reset trade tracking
        invItemCountAtTradeStart = 0;
        tradeItemReceivedMs = 0L;
        tradeGuiOpenedMs = 0L;
        
        currentTarget = null;
        currentApproachGoal = null;
        approachKind = ApproachKind.VILLAGER;
        currentChestPos = null;
        currentChestOpenSpot = null;

        resetOpenAttemptState();
        resetApproachTracking();

        usingDiagonal = false;

        nudgeTimes = 0;
        nudgeTicksRemaining = 0;
        nudgeStartPos = null;
        
        // Small delay before seeking next villager
        lastMoveMs = System.currentTimeMillis();
        lastMovePos = client.player.getPos();
        nextSeekAllowedMs = System.currentTimeMillis() + 150L; // 150ms delay

        state = State.SEEK;
        showSeekStatus(client);
        dbg("WAIT_CLOSE -> SEEK");
    }
    
    private void showSeekStatus(MinecraftClient client) {
        int floorY = currentFloorKeyY(client);
        String inItem = StorageRegistry.getRememberedItem(Role.INPUT, floorY)
            .map(id -> id.getPath()).orElse("?");
        String outItem = StorageRegistry.getRememberedItem(Role.OUTPUT, floorY)
            .map(id -> id.getPath()).orElse("?");
        statusThrottled(client, "👀 Seeking | in:" + inItem + " out:" + outItem);
    }
    
    private int countTotalInventoryItems(MinecraftClient client) {
        if (client.player == null) return 0;
        int count = 0;
        for (int i = 0; i < client.player.getInventory().main.size(); i++) {
            var stack = client.player.getInventory().main.get(i);
            if (stack != null && !stack.isEmpty()) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static double distSqToGoal(MinecraftClient client, BlockPos goal) {
        Vec3d p = client.player.getPos();
        double gx = goal.getX() + 0.5;
        double gy = goal.getY();
        double gz = goal.getZ() + 0.5;
        double dx = p.x - gx;
        double dy = p.y - gy;
        double dz = p.z - gz;
        return dx * dx + dy * dy + dz * dz;
    }

    private static double horizDistSq(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private boolean isDiagonalApproach(VillagerEntity villager, BlockPos approachPos) {
        if (villager == null || approachPos == null) return false;
        BlockPos base = villager.getBlockPos();
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos straight = base.offset(dir, 2);
            if (straight.equals(approachPos)) return false;
        }
        return true;
    }

    private void lookAtBlock(MinecraftClient client, BlockPos pos) {
        if (client == null || client.player == null || pos == null) return;

        Vec3d eye = client.player.getEyePos();
        Vec3d target = new Vec3d(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5);

        double dx = target.x - eye.x;
        double dy = target.y - eye.y;
        double dz = target.z - eye.z;

        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0F);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, distXZ)));

        client.player.setYaw(yaw);
        client.player.setPitch(pitch);
    }

    private void faceEntity(MinecraftClient client, VillagerEntity target) {
        if (client.player == null) return;

        Vec3d eyePos = client.player.getEyePos();
        double villagerEyeY = target.getY() + target.getStandingEyeHeight();
        Vec3d targetPos = new Vec3d(target.getX(), villagerEyeY, target.getZ());

        double dx = targetPos.x - eyePos.x;
        double dy = targetPos.y - eyePos.y;
        double dz = targetPos.z - eyePos.z;

        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0F);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, distXZ)));

        client.player.setYaw(yaw);
        client.player.setPitch(pitch);
    }
    
    private void faceBlock(MinecraftClient client, BlockPos target) {
        if (client.player == null || target == null) return;

        Vec3d eyePos = client.player.getEyePos();
        Vec3d targetPos = new Vec3d(target.getX() + 0.5, target.getY() + 0.5, target.getZ() + 0.5);

        double dx = targetPos.x - eyePos.x;
        double dy = targetPos.y - eyePos.y;
        double dz = targetPos.z - eyePos.z;

        double distXZ = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0F);
        float pitch = (float) (-Math.toDegrees(Math.atan2(dy, distXZ)));

        client.player.setYaw(yaw);
        client.player.setPitch(pitch);
    }

    private void spamUseKey(MinecraftClient client, long now) {
        if (client == null || client.options == null) return;
        var useKey = client.options.useKey;
        if (useKey == null) return;

        int clickRate = TradeRunSettings.get().clickRateMs;
        long useIntervalMs = Math.max(150L, clickRate);

        if (!useKeyHeld) {
            if (now - lastUseToggleMs >= useIntervalMs) {
                useKey.setPressed(true);
                useKeyHeld = true;
                lastUseToggleMs = now;
            }
        } else {
            if (now - lastUseToggleMs >= USE_PRESS_MS) {
                useKey.setPressed(false);
                useKeyHeld = false;
                lastUseToggleMs = now;
            }
        }
    }

    private void releaseUseKey(MinecraftClient client) {
        if (client == null || client.options == null) return;
        var useKey = client.options.useKey;
        if (useKey == null) return;
        if (useKeyHeld) {
            useKey.setPressed(false);
            useKeyHeld = false;
        }
    }

    private void pressForwardKey(MinecraftClient client, boolean pressed) {
        if (client == null || client.options == null) return;
        var key = client.options.forwardKey;
        if (key == null) return;

        if (pressed) {
            key.setPressed(true);
            forwardKeyForced = true;
        } else {
            if (forwardKeyForced) key.setPressed(false);
            forwardKeyForced = false;
        }
    }

    private void releaseForwardKey(MinecraftClient client) {
        pressForwardKey(client, false);
    }
    
    // ---- Floor Transition ----
    
    private static final long FLOOR_TRANSITION_TIMEOUT_MS = 30000L; // 30 seconds max
    
    private void tickFloorTransition(MinecraftClient client) {
        if (client.player == null || client.world == null) return;
        
        int currentY = client.player.getBlockPos().getY();
        long now = System.currentTimeMillis();
        
        if (floorTransitionStartY == 0 && client.player != null) {
            floorTransitionStartY = client.player.getBlockPos().getY();
        }
        boolean goingUp = targetFloorY > floorTransitionStartY;

        // Check if we've reached the target floor (directional)
        // Going up: require currentY >= target Y
        // Going down: require currentY <= target Y
        boolean reached = goingUp ? currentY >= targetFloorY : currentY <= targetFloorY;
        if (reached) {
            navigator.stop();
            releaseForwardKey(client);
            say(client, "Reached floor Y=" + currentY);
            dbg("FLOOR_TRANSITION complete, arrived at Y=" + currentY);
            
            // Update primary floor Y to the TARGET (not current - current might be higher)
            primaryFloorY = targetFloorY;
            int arrivedFloorY = targetFloorY;
            arrivedOnFloorMs = System.currentTimeMillis();  // Track arrival time
            
            // Reset transition state
            targetFloorY = 0;
            transitionPoint = null;
            floorTransitionPhase = 0;
            floorTransitionStartY = 0;
            
            // Check if we need to restock for this floor's input item
            Identifier inputId = StorageRegistry.getRememberedItem(Role.INPUT, arrivedFloorY).orElse(null);
            int haveInput = (inputId != null) ? countItemById(client, inputId) : 0;
            
            if (haveInput < 32 && inputId != null) {
                // Don't have enough input items for this floor - go restock first
                dbg("floor arrival: need restock, have " + haveInput + " of " + inputId.getPath());
                currentChestPos = null;
                currentChestOpenSpot = null;
                currentApproachGoal = null;
                containerSession = null;
                state = State.DETOUR_RESTOCK;
            } else {
                state = State.SEEK;
            }
            return;
        }
        
        // Timeout check
        if (floorTransitionStartMs > 0 && now - floorTransitionStartMs > FLOOR_TRANSITION_TIMEOUT_MS) {
            navigator.stop();
            releaseForwardKey(client);
            say(client, "Floor transition timeout - couldn't reach Y=" + targetFloorY);
            dbg("FLOOR_TRANSITION timeout");
            
            targetFloorY = 0;
            transitionPoint = null;
            floorTransitionPhase = 0;
            state = State.SEEK;
            return;
        }
        
        // Simple approach: just navigate to the transition point using Baritone
        // Baritone handles stairs, ladders, etc. automatically
        if (transitionPoint == null) {
            state = State.SEEK;
            return;
        }
        
        // Check if we're close enough horizontally (Y is checked at the top)
        double distSq = client.player.squaredDistanceTo(
            transitionPoint.getX() + 0.5, 
            client.player.getY(), 
            transitionPoint.getZ() + 0.5
        );
        
        if (distSq < 4.0) {
            // Close enough horizontally - let Baritone handle the Y movement
            dbg("FLOOR_TRANSITION: near target, letting Baritone finish");
        }
        
        // Keep navigating to the transition point
        if (navigator.getActiveGoal() == null || !navigator.getActiveGoal().equals(transitionPoint)) {
            navigator.gotoFloorPosition(client, transitionPoint);
        }
    }
}



