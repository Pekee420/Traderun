package com.example.traderun.runtime;

/**
 * Manages the 10-second ESC pause functionality.
 * Separate from mixin to avoid static method issues.
 */
public final class EscPauseManager {
    
    private static long pauseUntilMs = 0L;
    
    private EscPauseManager() {}
    
    /**
     * Start a 10-second pause
     */
    public static void startPause() {
        // Don't restart if already in pause
        if (isInPause()) {
            return;
        }
        pauseUntilMs = System.currentTimeMillis() + 10_000L;
    }
    
    /**
     * Clear the pause (when mod stops or user sneaks)
     */
    public static void clearPause() {
        pauseUntilMs = 0L;
    }
    
    /**
     * Check if currently in pause mode
     */
    public static boolean isInPause() {
        return System.currentTimeMillis() < pauseUntilMs;
    }
    
    /**
     * Mark that we just closed ESC
     */
    public static void markClosingEsc() {
        pauseUntilMs = 0L;
    }
    
    /**
     * Get remaining seconds in pause
     */
    public static int getRemainingSeconds() {
        long remaining = pauseUntilMs - System.currentTimeMillis();
        return remaining > 0 ? (int)(remaining / 1000) + 1 : 0;
    }
}
