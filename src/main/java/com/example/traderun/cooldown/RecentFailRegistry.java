package com.example.traderun.cooldown;

import net.minecraft.entity.passive.VillagerEntity;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Temporarily suppresses villagers that we failed to interact with recently.
 * Prevents repeatedly trying the same villager when pathfinding or interaction fails.
 * Uses SHORT cooldowns to keep the bot active - it should always be doing something.
 */
public final class RecentFailRegistry {

    private static final long FAIL_MS = 5_000L; // 5 seconds - try others, come back
    private static final long SECOND_FAIL_MS = 15_000L; // 15 seconds - skip for a bit longer on repeated failure
    
    private static final Map<UUID, Long> UNTIL_MS = new ConcurrentHashMap<>();
    
    // Track failures - second failure gets slightly longer cooldown
    private static final Map<UUID, Integer> FAIL_COUNT = new ConcurrentHashMap<>();

    private RecentFailRegistry() {}

    /**
     * Mark a simple failure (navigation error, timeout, etc.)
     * Short cooldown - try other villagers, come back to this one.
     */
    public static void markFailure(VillagerEntity villager) {
        if (villager == null) return;
        UNTIL_MS.put(villager.getUuid(), System.currentTimeMillis() + FAIL_MS);
    }
    
    /**
     * Mark a villager as having no valid approach position.
     * Uses slightly longer cooldown but still keeps bot active.
     */
    public static void markNoApproachFailure(VillagerEntity villager) {
        if (villager == null) return;
        UNTIL_MS.put(villager.getUuid(), System.currentTimeMillis() + SECOND_FAIL_MS);
    }
    
    /**
     * Mark diagonal/blocked approach failure with retry logic.
     * First failure: 5s cooldown (try other villagers, then retry)
     * Second failure: 15s cooldown (skip for a bit, but don't idle)
     * @return true if this was a second failure
     */
    public static boolean markDiagonalFailure(VillagerEntity villager) {
        if (villager == null) return false;
        UUID id = villager.getUuid();
        int count = FAIL_COUNT.getOrDefault(id, 0) + 1;
        FAIL_COUNT.put(id, count);
        
        if (count >= 2) {
            // Second failure - slightly longer cooldown
            UNTIL_MS.put(id, System.currentTimeMillis() + SECOND_FAIL_MS);
            return true;
        } else {
            // First failure - short cooldown, will retry after other villagers
            UNTIL_MS.put(id, System.currentTimeMillis() + FAIL_MS);
            return false;
        }
    }
    
    /**
     * Clear failure count for a villager (call after successful trade with ANY villager)
     */
    public static void clearFailCounts() {
        FAIL_COUNT.clear();
    }

    public static boolean isSuppressed(VillagerEntity villager) {
        if (villager == null) return false;
        long now = System.currentTimeMillis();
        
        Long until = UNTIL_MS.get(villager.getUuid());
        if (until != null) {
            if (now < until) return true;
            UNTIL_MS.remove(villager.getUuid());
        }
        
        return false;
    }

    /**
     * Clear all short-term failures (for when all villagers are suppressed).
     */
    public static void clearAll() {
        UNTIL_MS.clear();
    }

    public static void clear() {
        clearAll();
    }
    
    /**
     * Full reset - called when user manually restarts traderun.
     */
    public static void fullReset() {
        UNTIL_MS.clear();
        FAIL_COUNT.clear();
    }
}
