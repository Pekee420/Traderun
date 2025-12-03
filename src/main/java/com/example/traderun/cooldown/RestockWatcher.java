package com.example.traderun.cooldown;

import net.minecraft.client.MinecraftClient;

/**
 * Placeholder for restock detection.
 * Currently uses timer-based cooldowns only (more reliable than particle detection).
 */
public class RestockWatcher {
    
    private RestockWatcher() {}
    
    /**
     * Called each tick (currently unused).
     */
    public static void tick(MinecraftClient client) {
        // Timer-based cooldowns are handled by CooldownRegistry
    }
    
    /**
     * Reset state (call when starting a new session).
     */
    public static void reset() {
        // Nothing to reset for timer-based cooldowns
    }
}
