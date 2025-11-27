package com.example.traderun.cooldown;

import com.example.traderun.util.DebugLogger;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.network.packet.s2c.play.ParticleS2CPacket;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.Box;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Watches for villager restock events by intercepting particle packets.
 * 
 * When villagers restock, the server sends HAPPY_VILLAGER particles.
 * This works even with particles disabled (norender) because we intercept
 * the network packet before rendering.
 */
public class RestockWatcher {
    
    private static final double PARTICLE_RANGE = 2.0; // How close particle must be to villager
    private static final long TRADE_GRACE_PERIOD_MS = 5000L; // Ignore particles for 5s after trading
    
    // Track when we last traded with each villager (to ignore post-trade particles)
    private static final Map<UUID, Long> recentlyTradedUntil = new ConcurrentHashMap<>();
    
    private RestockWatcher() {}
    
    /**
     * Call this when we finish trading with a villager.
     * Particles from this villager will be ignored for a few seconds.
     */
    public static void markJustTraded(VillagerEntity villager) {
        if (villager == null) return;
        recentlyTradedUntil.put(villager.getUuid(), System.currentTimeMillis() + TRADE_GRACE_PERIOD_MS);
    }
    
    /**
     * Called from mixin when a particle packet is received.
     */
    public static void onParticlePacket(ParticleS2CPacket packet) {
        // Only care about HAPPY_VILLAGER particles (green sparkles = restock)
        if (packet.getParameters().getType() != ParticleTypes.HAPPY_VILLAGER) {
            return;
        }
        
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.world == null || client.player == null) return;
        
        double px = packet.getX();
        double py = packet.getY();
        double pz = packet.getZ();
        
        // Find villagers near this particle
        Box searchBox = new Box(
            px - PARTICLE_RANGE, py - PARTICLE_RANGE, pz - PARTICLE_RANGE,
            px + PARTICLE_RANGE, py + PARTICLE_RANGE, pz + PARTICLE_RANGE
        );
        
        List<VillagerEntity> nearbyVillagers = client.world.getEntitiesByClass(
            VillagerEntity.class, searchBox, v -> true
        );
        
        long now = System.currentTimeMillis();
        
        for (VillagerEntity villager : nearbyVillagers) {
            UUID id = villager.getUuid();
            
            // Skip if we just traded with this villager (particles are from the trade, not restock)
            Long ignoreUntil = recentlyTradedUntil.get(id);
            if (ignoreUntil != null) {
                if (now < ignoreUntil) {
                    continue; // Still in grace period, ignore
                } else {
                    recentlyTradedUntil.remove(id); // Grace period over
                }
            }
            
            if (CooldownRegistry.isOnCooldown(villager)) {
                // This villager just restocked! Clear their cooldown
                CooldownRegistry.clearCooldown(villager);
                DebugLogger.log("RestockWatcher: detected restock particle near villager, cleared cooldown");
            }
        }
    }
    
    /**
     * Called each tick (currently unused but kept for interface compatibility).
     */
    public static void tick(MinecraftClient client) {
        // Particle detection is handled by the mixin, nothing to do here
    }
    
    /**
     * Reset state (call when starting a new session).
     */
    public static void reset() {
        recentlyTradedUntil.clear();
    }
}

