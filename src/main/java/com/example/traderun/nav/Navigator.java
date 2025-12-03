package com.example.traderun.nav;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Method;

/**
 * Navigator using ApproachUtil for position finding.
 * Uses Baritone API via reflection (NO CHAT) + direct walk fallback.
 * NEVER sends coordinates to chat.
 */
public final class Navigator {

    private BlockPos activeGoal = null;
    private long lastGoalSetMs = 0L;
    private boolean baritoneAvailable = false;
    private boolean gotoIssued = false;
    private String lastError = null;
    private boolean baritoneConfigured = false;
    private boolean lastApproachWasDiagonal = false;

    // Direct walk
    private boolean directWalkActive = false;
    private long directWalkStartMs = 0L;
    private Vec3d lastDirectWalkPos = null;
    private long lastDirectWalkMoveMs = 0L;
    private double directWalkStartY = 0.0;
    
    // Y-level enforcement (disable during floor transitions)
    private boolean allowYLevelChanges = false;

    // Baritone fallback timing
    private Vec3d posAtGoalSet = null;
    private long goalSetTimeMs = 0L;
    private static final long BARITONE_MOVE_TIMEOUT_MS = 800L;

    // ---- Public API ----

    public BlockPos gotoVillagerApproachPoint(MinecraftClient client, VillagerEntity villager) {
        if (client == null || client.player == null || client.world == null || villager == null) return null;
        
        // PRIORITY 1: Try straight approach first
        BlockPos target = ApproachUtil.findBestStraightApproach(client, villager);
        if (target != null) {
            lastApproachWasDiagonal = false;
            setGoalVillagerApproach(client, target);
            return target;
        }
        
        // PRIORITY 2: Fall back to diagonal only if straight not possible
        target = ApproachUtil.findBestApproach(client, villager);
        if (target == null) {
            lastError = "no valid approach position";
            return null;
        }
        
        lastApproachWasDiagonal = true;
        setGoalVillagerApproach(client, target);
        return target;
    }

    public BlockPos gotoVillagerApproachPointAlternate(MinecraftClient client, VillagerEntity villager, BlockPos exclude) {
        if (client == null || client.player == null || client.world == null || villager == null) return null;
        
        // Try to find any valid position excluding the failed one
        BlockPos target = ApproachUtil.findBestApproachExcluding(client, villager, exclude);
        if (target == null) {
            lastError = "no alternate approach position";
            return null;
        }
        
        lastApproachWasDiagonal = !isStraightFromVillager(villager, target);
        setGoalVillagerApproach(client, target);
        return target;
    }

    public BlockPos gotoVillagerStraightApproach(MinecraftClient client, VillagerEntity villager) {
        if (client == null || client.player == null || client.world == null || villager == null) return null;
        
        BlockPos target = ApproachUtil.findBestStraightApproach(client, villager);
        if (target == null) {
            // Fall back to any approach
            target = ApproachUtil.findBestApproach(client, villager);
        }
        if (target == null) {
            lastError = "no approach position";
            return null;
        }
        
        lastApproachWasDiagonal = !isStraightFromVillager(villager, target);
        setGoalVillagerApproach(client, target);
        return target;
    }

    public BlockPos gotoBlockApproach(MinecraftClient client, BlockPos target) {
        if (client == null || client.player == null || target == null) return null;
        // For chests, navigate to exact position (saved open spot)
        setGoal(client, target);
        return target.toImmutable();
    }
    
    /**
     * Navigate to exact position (for saved storage open spots).
     * Uses same-floor navigation with Y-level enforcement.
     */
    public BlockPos gotoExactPosition(MinecraftClient client, BlockPos target) {
        if (client == null || client.player == null || target == null) return null;
        setGoal(client, target);
        return target.toImmutable();
    }
    
    /**
     * Navigate to a position on a different floor (for floor transitions).
     * Allows Y-level changes.
     */
    public BlockPos gotoFloorPosition(MinecraftClient client, BlockPos target) {
        if (client == null || client.player == null || target == null) return null;
        setGoalAllowDifferentFloor(client, target);
        return target.toImmutable();
    }
    
    /**
     * Navigate to storage position (allows small Y differences for chests on platforms).
     * Allows up to 4 blocks Y difference to handle shulkers on shelves.
     */
    public BlockPos gotoStoragePosition(MinecraftClient client, BlockPos target) {
        if (client == null || client.player == null || target == null) return null;
        int playerY = client.player.getBlockPos().getY();
        // Allow up to 4 Y difference for storage (shulkers on platforms)
        if (Math.abs(target.getY() - playerY) <= 4) {
            setGoalAllowDifferentFloor(client, target);
            return target.toImmutable();
        } else {
            // Too far in Y - storage is on a different floor, reject
            lastError = "storage too far in Y (different floor?)";
            return null;
        }
    }

    public void stop() {
        cancelBaritone();
        stopDirectWalk();
        activeGoal = null;
        gotoIssued = false;
        posAtGoalSet = null;
    }
    
    /**
     * Emergency escape - walk to a random nearby position using Baritone without Y limits.
     * Used when stuck on obstacles like brewing stands.
     * Returns the target position or null if failed.
     */
    public BlockPos escapeToNearby(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) return null;
        
        stop(); // Clear any current navigation
        
        BlockPos playerPos = client.player.getBlockPos();
        
        // Try close positions first (1-2 blocks), then farther (3-5 blocks)
        // This helps in tight 2-block high spaces
        int[][] distances = {{1, -1, 2, -2}, {3, -3, 4, -4, 5, -5}};
        java.util.Random rand = new java.util.Random();
        
        for (int[] offsets : distances) {
            for (int attempt = 0; attempt < 12; attempt++) {
                int dx = offsets[rand.nextInt(offsets.length)];
                int dz = offsets[rand.nextInt(offsets.length)];
                
                // Skip if both dx and dz are the same (diagonal in same direction)
                if (attempt > 6 && dx == dz) continue;
                
                // Look for ground at different Y levels (prioritize same level and down)
                for (int dy : new int[]{0, -1, 1, -2, 2}) {
                    BlockPos target = playerPos.add(dx, dy, dz);
                    
                    // Check if it's a valid standing position (2 blocks high space)
                    boolean groundSolid = client.world.getBlockState(target.down()).isSolid();
                    boolean feetClear = !client.world.getBlockState(target).isSolid();
                    boolean headClear = !client.world.getBlockState(target.up()).isSolid();
                    
                    if (groundSolid && feetClear && headClear) {
                        // Use Baritone to navigate there with no Y restrictions
                        setGoalAllowDifferentFloor(client, target);
                        return target;
                    }
                }
            }
        }
        
        lastError = "no escape position found";
        return null;
    }

    public boolean isBaritoneAvailable() { return baritoneAvailable; }
    public boolean wasGotoIssued() { return gotoIssued; }
    public BlockPos getActiveGoal() { return activeGoal; }
    public String getLastError() { return lastError; }
    public void clearLastError() { lastError = null; }
    public boolean isDirectWalkActive() { return directWalkActive; }
    public boolean wasLastApproachDiagonal() { return lastApproachWasDiagonal; }
    
    /**
     * Allow or disallow Y-level changes during direct walk.
     * Call setAllowYLevelChanges(true) before floor transitions.
     * Call setAllowYLevelChanges(false) when trading on a floor.
     */
    public void setAllowYLevelChanges(boolean allow) { this.allowYLevelChanges = allow; }
    
    /** Force clear rate limit to allow immediate re-navigation */
    public void clearRateLimit() {
        lastGoalSetMs = 0L;
        activeGoal = null;
    }

    // ---- Goal Setting (NO CHAT EVER) ----

    private void setGoal(MinecraftClient client, BlockPos goal) {
        setGoalInternal(client, goal, false, 1);
    }
    
    private void setGoalAllowDifferentFloor(MinecraftClient client, BlockPos goal) {
        setGoalInternal(client, goal, true, 999);
    }
    
    // For villager approach - allow up to 3 blocks Y difference (villagers may be on platforms)
    private void setGoalVillagerApproach(MinecraftClient client, BlockPos goal) {
        setGoalInternal(client, goal, false, 3);
    }
    
    private void setGoalInternal(MinecraftClient client, BlockPos goal, boolean allowDifferentFloor, int yTolerance) {
        if (goal == null || client == null || client.player == null) return;

        long now = System.currentTimeMillis();
        
        // Rate limit
        if (activeGoal != null && activeGoal.equals(goal) && (now - lastGoalSetMs) < 1500L) {
            return;
        }
        if ((now - lastGoalSetMs) < 250L) return;

        stopDirectWalk();
        cancelBaritone();

        int playerY = client.player.getBlockPos().getY();
        
        // Reject goals on different Y level (unless allowed)
        if (!allowDifferentFloor && Math.abs(goal.getY() - playerY) > yTolerance) {
            lastError = "goal on different floor (y diff=" + Math.abs(goal.getY() - playerY) + ", tolerance=" + yTolerance + ")";
            activeGoal = null;
            return;
        }

        activeGoal = goal.toImmutable();
        lastGoalSetMs = now;
        goalSetTimeMs = now;
        posAtGoalSet = client.player.getPos();
        lastError = null;

        // Configure Baritone (only once)
        configureBaritone();

        // Try Baritone API first (via reflection, NO CHAT)
        boolean baritoneOk = tryBaritoneGoalProcess(goal);
        if (!baritoneOk) {
            baritoneOk = tryBaritonePathingBehavior(goal);
        }

        if (baritoneOk) {
            baritoneAvailable = true;
            gotoIssued = true;
            // Also start direct walk as backup - it will be stopped if Baritone works
            startDirectWalk(client);
        } else {
            // Baritone failed, use direct walk immediately
            baritoneAvailable = false;
            gotoIssued = false;
            startDirectWalk(client);
        }
    }

    // ---- Direct Walk ----

    public void tickDirectWalk(MinecraftClient client) {
        if (client == null || client.player == null) return;

        // NEVER use direct walk fallback for vertical navigation (floor transitions)
        // Direct walk can't handle stairs/ladders and will just walk into walls
        if (allowYLevelChanges && activeGoal != null) {
            int playerY = client.player.getBlockPos().getY();
            if (Math.abs(activeGoal.getY() - playerY) > 2) {
                // Different floor and more than 2 blocks away vertically - don't use direct walk
                return;
            }
        }

        // Check if Baritone was issued but player hasn't moved - switch to direct walk
        if (gotoIssued && !directWalkActive && posAtGoalSet != null && activeGoal != null) {
            long elapsed = System.currentTimeMillis() - goalSetTimeMs;
            if (elapsed > BARITONE_MOVE_TIMEOUT_MS) {
                Vec3d currentPos = client.player.getPos();
                double movedSq = horizDistSq(currentPos, posAtGoalSet);
                if (movedSq < 0.01) {
                    // Don't fall back to direct walk for vertical navigation
                    if (allowYLevelChanges && Math.abs(activeGoal.getY() - client.player.getBlockPos().getY()) > 2) {
                        return; // Let Baritone keep trying
                    }
                    cancelBaritone();
                    startDirectWalk(client);
                }
            }
        }

        if (!directWalkActive || activeGoal == null) return;

        Vec3d playerPos = client.player.getPos();
        long now = System.currentTimeMillis();

        // Stop if Y changed significantly - but ONLY for same-floor navigation
        // If allowYLevelChanges is true (floor transitions), skip this check entirely
        if (!allowYLevelChanges) {
            boolean sameFloorGoal = Math.abs(activeGoal.getY() - directWalkStartY) < 1.5;
            if (sameFloorGoal) {
                double yDiff = playerPos.y - directWalkStartY;
                // Detect falls early (>0.8 blocks) to stop before going too far
                // Allow small climbs (0.6) for minor variations but catch stepping on blocks
                if (yDiff < -0.8 || yDiff > 0.6) {
                    stopDirectWalk();
                    lastError = yDiff > 0 ? "climbed onto block" : "Y level dropped";
                    return;
                }
            }
        }
        
        // EDGE DETECTION: Check if there's a hole in front of us
        if (!allowYLevelChanges && client.world != null) {
            double gx = activeGoal.getX() + 0.5;
            double gz = activeGoal.getZ() + 0.5;
            double pdx = gx - playerPos.x;
            double pdz = gz - playerPos.z;
            double len = Math.sqrt(pdx * pdx + pdz * pdz);
            if (len > 0.1) {
                // Normalize and look 1.5 blocks ahead
                pdx = pdx / len * 1.5;
                pdz = pdz / len * 1.5;
                BlockPos ahead = BlockPos.ofFloored(playerPos.x + pdx, playerPos.y - 0.5, playerPos.z + pdz);
                BlockPos aheadDown = ahead.down();
                // If there's no ground ahead, stop walking
                if (!client.world.getBlockState(aheadDown).isSolid() && !client.world.getBlockState(ahead).isSolid()) {
                    stopDirectWalk();
                    lastError = "hole detected ahead";
                    return;
                }
            }
        }

        double gx = activeGoal.getX() + 0.5;
        double gz = activeGoal.getZ() + 0.5;
        double dx = gx - playerPos.x;
        double dz = gz - playerPos.z;
        double distSq = dx * dx + dz * dz;

        // Close enough - but check Y level too if we're supposed to be at different floor
        if (distSq < 0.5 * 0.5) {
            // If goal is on different Y, make sure we're at the right Y level
            if (allowYLevelChanges) {
                int playerY = client.player.getBlockPos().getY();
                if (Math.abs(activeGoal.getY() - playerY) > 1) {
                    // Horizontally close but wrong Y - don't stop yet
                    return;
                }
            }
            stopDirectWalk();
            return;
        }
        
        // Timeout after 8s
        if (now - directWalkStartMs > 8000L) {
            stopDirectWalk();
            lastError = "direct walk timeout";
            return;
        }

        // Check if stuck
        if (lastDirectWalkPos != null) {
            double movedSq = horizDistSq(playerPos, lastDirectWalkPos);
            if (movedSq > 0.01) {
                lastDirectWalkPos = playerPos;
                lastDirectWalkMoveMs = now;
            } else if (now - lastDirectWalkMoveMs > 600L) {
                stopDirectWalk();
                lastError = "direct walk stuck";
                return;
            }
        } else {
            lastDirectWalkPos = playerPos;
            lastDirectWalkMoveMs = now;
        }

        // Face goal and walk
        float yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0F);
        client.player.setYaw(yaw);

        if (client.options != null && client.options.forwardKey != null) {
            client.options.forwardKey.setPressed(true);
        }
    }

    private void startDirectWalk(MinecraftClient client) {
        directWalkActive = true;
        directWalkStartMs = System.currentTimeMillis();
        lastDirectWalkPos = null;
        lastDirectWalkMoveMs = System.currentTimeMillis();
        if (client != null && client.player != null) {
            directWalkStartY = client.player.getPos().y;
        }
    }

    private void stopDirectWalk() {
        if (directWalkActive) {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.options != null && client.options.forwardKey != null) {
                client.options.forwardKey.setPressed(false);
            }
        }
        directWalkActive = false;
    }

    private static double horizDistSq(Vec3d a, Vec3d b) {
        double dx = a.x - b.x;
        double dz = a.z - b.z;
        return dx * dx + dz * dz;
    }

    private boolean isStraightFromVillager(VillagerEntity v, BlockPos pos) {
        BlockPos vPos = v.getBlockPos();
        return pos.getX() == vPos.getX() || pos.getZ() == vPos.getZ();
    }

    // ---- Baritone Reflection (NO CHAT COMMANDS) ----

    // Static flag - only configure Baritone ONCE per Minecraft session
    private static boolean baritoneConfiguredThisSession = false;
    
    /**
     * Force configure Baritone safety settings. Only runs ONCE per session.
     */
    public void forceConfigureBaritone() {
        if (baritoneConfiguredThisSession) {
            return;  // Already configured this session, don't spam
        }
        baritoneConfigured = false;
        configureBaritone();
    }

    private void configureBaritone() {
        // Try reflection first (silent)
        boolean reflectionWorked = tryConfigureViaReflection();
        
        if (!reflectionWorked) {
            // Only use command manager fallback if reflection failed
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.player != null) {
                pendingBaritoneCommands.clear();
                pendingBaritoneCommands.add("set allowBreak false");
                pendingBaritoneCommands.add("set allowBreakAnyway false");
                pendingBaritoneCommands.add("set allowPlace false");
            }
        }
        
        baritoneConfigured = true;
        baritoneConfiguredThisSession = true;  // Mark as done for this session
    }
    
    // Queue of Baritone commands to send via command manager
    private final java.util.Deque<String> pendingBaritoneCommands = new java.util.ArrayDeque<>();
    private long lastBaritoneCommandMs = 0L;
    
    /**
     * Call this from tick to send pending Baritone commands (one per 200ms)
     */
    public void tickBaritoneCommands() {
        if (pendingBaritoneCommands.isEmpty()) return;
        
        long now = System.currentTimeMillis();
        if (now - lastBaritoneCommandMs < 200L) return;
        
        String cmd = pendingBaritoneCommands.pollFirst();
        if (cmd != null) {
            try {
                Class<?> baritoneAPI = Class.forName("baritone.api.BaritoneAPI");
                Method getProvider = baritoneAPI.getMethod("getProvider");
                Object provider = getProvider.invoke(null);
                Method getPrimary = provider.getClass().getMethod("getPrimaryBaritone");
                Object baritone = getPrimary.invoke(provider);
                Method getCommandManager = baritone.getClass().getMethod("getCommandManager");
                Object cmdMgr = getCommandManager.invoke(baritone);
                Method execute = cmdMgr.getClass().getMethod("execute", String.class);
                execute.invoke(cmdMgr, cmd);
            } catch (Throwable ignored) {}
            lastBaritoneCommandMs = now;
        }
    }
    
    private boolean tryConfigureViaReflection() {
        try {
            Class<?> baritoneAPI = Class.forName("baritone.api.BaritoneAPI");
            Method getSettings = baritoneAPI.getMethod("getSettings");
            Object settings = getSettings.invoke(null);
            if (settings == null) return false;

            setBoolSafe(settings, "allowBreak", false);
            setBoolSafe(settings, "allowBreakAnyway", false);
            setBoolSafe(settings, "allowPlace", false);
            setBoolSafe(settings, "allowInventory", false);
            setBoolSafe(settings, "allowParkour", false);
            setBoolSafe(settings, "allowParkourPlace", false);

            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private void setBoolSafe(Object settings, String name, boolean value) {
        try {
            java.lang.reflect.Field field = settings.getClass().getField(name);
            Object setting = field.get(settings);
            if (setting != null) {
                Method m = setting.getClass().getMethod("set", Object.class);
                m.invoke(setting, value);
            }
        } catch (Throwable ignored) {}
    }

    private boolean tryBaritoneGoalProcess(BlockPos goal) {
        try {
            Class<?> baritoneAPI = Class.forName("baritone.api.BaritoneAPI");
            Method getProvider = baritoneAPI.getMethod("getProvider");
            Object provider = getProvider.invoke(null);

            Method getPrimary = provider.getClass().getMethod("getPrimaryBaritone");
            Object baritone = getPrimary.invoke(provider);

            Method getCustomGoalProcess = baritone.getClass().getMethod("getCustomGoalProcess");
            Object goalProcess = getCustomGoalProcess.invoke(baritone);

            Class<?> goalBlockClz = Class.forName("baritone.api.pathing.goals.GoalBlock");
            Object goalBlock = goalBlockClz
                    .getConstructor(int.class, int.class, int.class)
                    .newInstance(goal.getX(), goal.getY(), goal.getZ());

            Class<?> goalInterface = Class.forName("baritone.api.pathing.goals.Goal");
            Method setGoalAndPath = goalProcess.getClass().getMethod("setGoalAndPath", goalInterface);
            setGoalAndPath.invoke(goalProcess, goalBlock);

            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean tryBaritonePathingBehavior(BlockPos goal) {
        try {
            Class<?> baritoneAPI = Class.forName("baritone.api.BaritoneAPI");
            Method getProvider = baritoneAPI.getMethod("getProvider");
            Object provider = getProvider.invoke(null);

            Method getPrimary = provider.getClass().getMethod("getPrimaryBaritone");
            Object baritone = getPrimary.invoke(provider);

            Method getPathingBehavior = baritone.getClass().getMethod("getPathingBehavior");
            Object pathingBehavior = getPathingBehavior.invoke(baritone);

            Class<?> goalBlockClz = Class.forName("baritone.api.pathing.goals.GoalBlock");
            Object goalBlock = goalBlockClz
                    .getConstructor(int.class, int.class, int.class)
                    .newInstance(goal.getX(), goal.getY(), goal.getZ());

            Class<?> goalInterface = Class.forName("baritone.api.pathing.goals.Goal");
            Method setGoal = pathingBehavior.getClass().getMethod("setGoal", goalInterface);
            setGoal.invoke(pathingBehavior, goalBlock);

            Method path = pathingBehavior.getClass().getMethod("path");
            path.invoke(pathingBehavior);

            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void cancelBaritone() {
        try {
            Class<?> baritoneAPI = Class.forName("baritone.api.BaritoneAPI");
            Method getProvider = baritoneAPI.getMethod("getProvider");
            Object provider = getProvider.invoke(null);

            Method getPrimary = provider.getClass().getMethod("getPrimaryBaritone");
            Object baritone = getPrimary.invoke(provider);

            try {
                Method getPathingBehavior = baritone.getClass().getMethod("getPathingBehavior");
                Object pb = getPathingBehavior.invoke(baritone);
                Method cancel = pb.getClass().getMethod("cancelEverything");
                cancel.invoke(pb);
            } catch (Throwable ignored) {}

            try {
                Method getCustomGoalProcess = baritone.getClass().getMethod("getCustomGoalProcess");
                Object gp = getCustomGoalProcess.invoke(baritone);
                Method cancel = gp.getClass().getMethod("cancelEverything");
                cancel.invoke(gp);
            } catch (Throwable ignored) {}

        } catch (Throwable ignored) {}
    }
}
