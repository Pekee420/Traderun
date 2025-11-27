package com.example.traderun.cooldown;

import com.example.traderun.config.TradeRunSettings;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.Box;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Per-villager cooldowns + subtle local marker.
 * 
 * DAY/NIGHT AWARE:
 * - Villagers can only restock trades during the day
 * - If cooldown expires during night, extend until next day
 * - If cooldown starts at night, extend until next day
 * 
 * IMPORTANT: coord-safe - no chat printing of coordinates, only local particles.
 */
public final class CooldownRegistry {

    private CooldownRegistry() {}

    // Minecraft time constants
    private static final long DAY_START = 0L;      // 6:00 AM - villagers wake up
    private static final long NIGHT_START = 12500L; // ~6:30 PM - villagers go to sleep
    private static final long DAY_LENGTH = 24000L;
    
    // Persistence
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type MAP_TYPE = new TypeToken<Map<String, Long>>(){}.getType();
    
    private static Path configPath() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("traderun");
        try { Files.createDirectories(dir); } catch (IOException ignored) {}
        return dir.resolve("cooldowns.json");
    }

    // Cooldown entry with time tracking
    private static class CooldownEntry {
        long cooldownUntilMs;      // System time when cooldown ends
        boolean startedDuringDay;  // Was it day when cooldown started?
        long worldTimeAtStart;     // World time when cooldown started
        
        CooldownEntry(long untilMs, boolean duringDay, long worldTime) {
            this.cooldownUntilMs = untilMs;
            this.startedDuringDay = duringDay;
            this.worldTimeAtStart = worldTime;
        }
    }

    private static final Map<UUID, CooldownEntry> cooldowns = new HashMap<>();
    
    static { load(); }  // Load on class init

    // Particle pacing (~0.8s)
    private static long nextParticleMs = 0L;

    /** Check if it's currently day in the world */
    public static boolean isDayTime(MinecraftClient client) {
        if (client == null || client.world == null) return true; // Default to day
        long worldTime = client.world.getTimeOfDay() % DAY_LENGTH;
        return worldTime >= DAY_START && worldTime < NIGHT_START;
    }

    /** Check if it's currently night in the world */
    public static boolean isNightTime(MinecraftClient client) {
        return !isDayTime(client);
    }

    /** Call when we consider a villager "traded" (Merchant screen opened). */
    public static void onVillagerTraded(VillagerEntity v) {
        if (v == null) return;
        MinecraftClient client = MinecraftClient.getInstance();
        
        long cooldownMs = TradeRunSettings.get().getCooldownMs();
        long worldTime = (client != null && client.world != null) ? client.world.getTimeOfDay() % DAY_LENGTH : 0L;
        boolean isDay = isDayTime(client);
        
        cooldowns.put(v.getUuid(), new CooldownEntry(
            System.currentTimeMillis() + cooldownMs,
            isDay,
            worldTime
        ));
        
        save();  // Persist to disk
    }

    public static boolean isOnCooldown(VillagerEntity v) {
        if (v == null) return false;
        return isOnCooldown(v.getUuid());
    }

    public static boolean isOnCooldown(UUID id) {
        if (id == null) return false;
        CooldownEntry entry = cooldowns.get(id);
        if (entry == null) return false;
        
        long now = System.currentTimeMillis();
        MinecraftClient client = MinecraftClient.getInstance();
        
        // Basic cooldown expired?
        if (now >= entry.cooldownUntilMs) {
            // Check night extension (only if enabled)
            if (TradeRunSettings.get().nightCooldownEnabled && isNightTime(client)) {
                // It's night - villager can't restock yet, stay on cooldown
                return true;
            }
            // Cooldown is truly over
            cooldowns.remove(id);
            return false;
        }
        
        // Cooldown timer not expired yet
        return true;
    }
    
    /**
     * Clear cooldown for a specific villager (e.g., when restock detected).
     */
    public static void clearCooldown(VillagerEntity v) {
        if (v == null) return;
        clearCooldown(v.getUuid());
    }
    
    public static void clearCooldown(UUID id) {
        if (id == null) return;
        if (cooldowns.remove(id) != null) {
            save();  // Persist change
        }
    }
    
    /**
     * Clear all cooldowns (e.g., when villager work time is detected).
     */
    public static void clearAllCooldowns() {
        if (!cooldowns.isEmpty()) {
            cooldowns.clear();
            save();
        }
    }

    public static void tick(MinecraftClient client) {
        if (client == null || client.world == null || client.player == null) return;

        long now = System.currentTimeMillis();
        boolean isDay = isDayTime(client);
        
        // Cleanup expired entries (only during day)
        if (isDay) {
            for (Iterator<Map.Entry<UUID, CooldownEntry>> it = cooldowns.entrySet().iterator(); it.hasNext(); ) {
                Map.Entry<UUID, CooldownEntry> e = it.next();
                if (now >= e.getValue().cooldownUntilMs) {
                    it.remove();
                }
            }
        }

        if (now < nextParticleMs) return;
        nextParticleMs = now + 800L;

        // Only show cooled villagers near you (local-only)
        double r = 32.0;
        Box box = client.player.getBoundingBox().expand(r, r, r);

        for (VillagerEntity v : client.world.getEntitiesByClass(VillagerEntity.class, box, VillagerEntity::isAlive)) {
            if (!isOnCooldown(v)) continue;

            double x = v.getX();
            double y = v.getY() + v.getHeight() + 0.2;
            double z = v.getZ();

            client.world.addParticle(ParticleTypes.NOTE, x, y, z, 0.0, 0.0, 0.0);
        }
    }

    public static void reset() {
        resetAll();
    }

    public static void resetAll() {
        cooldowns.clear();
        save();  // Clear the persisted file too
    }

    public static int count() {
        return cooldowns.size();
    }
    
    /** Get human-readable status for debugging */
    public static String getStatus(MinecraftClient client) {
        boolean isDay = isDayTime(client);
        long worldTime = (client != null && client.world != null) ? client.world.getTimeOfDay() % DAY_LENGTH : 0L;
        return "cooldowns=" + cooldowns.size() + " time=" + (isDay ? "DAY" : "NIGHT") + " (" + worldTime + ")";
    }
    
    // ===== Persistence =====
    
    private static void load() {
        Path path = configPath();
        if (!Files.exists(path)) return;
        
        try (Reader r = Files.newBufferedReader(path)) {
            Map<String, Long> stored = GSON.fromJson(r, MAP_TYPE);
            if (stored == null) return;
            
            long now = System.currentTimeMillis();
            for (Map.Entry<String, Long> e : stored.entrySet()) {
                try {
                    UUID uuid = UUID.fromString(e.getKey());
                    long untilMs = e.getValue();
                    
                    // Only load if cooldown hasn't expired yet
                    if (untilMs > now) {
                        cooldowns.put(uuid, new CooldownEntry(untilMs, true, 0L));
                    }
                } catch (IllegalArgumentException ignored) {
                    // Invalid UUID, skip
                }
            }
        } catch (Exception e) {
            // Ignore load errors
        }
    }
    
    private static void save() {
        try {
            Map<String, Long> toStore = new HashMap<>();
            long now = System.currentTimeMillis();
            
            for (Map.Entry<UUID, CooldownEntry> e : cooldowns.entrySet()) {
                // Only save non-expired cooldowns
                if (e.getValue().cooldownUntilMs > now) {
                    toStore.put(e.getKey().toString(), e.getValue().cooldownUntilMs);
                }
            }
            
            Path path = configPath();
            try (Writer w = Files.newBufferedWriter(path)) {
                GSON.toJson(toStore, w);
            }
        } catch (Exception e) {
            // Ignore save errors
        }
    }
}
