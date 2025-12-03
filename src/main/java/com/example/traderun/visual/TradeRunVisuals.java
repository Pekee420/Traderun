package com.example.traderun.visual;

import com.example.traderun.cooldown.CooldownRegistry;
import com.example.traderun.runtime.TradeRunRuntime;
import com.example.traderun.storage.StorageRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.Optional;

/**
 * Visual markers for TradeRun:
 * - Green particle on INPUT chests
 * - Red/flame particle on OUTPUT chests  
 * - Bright END_ROD marker on villagers on cooldown (very visible, minimal particles)
 */
public class TradeRunVisuals {
    
    private static long lastTickMs = 0L;
    private static final long TICK_INTERVAL_MS = 400L; // Update every 0.4s
    
    private TradeRunVisuals() {}
    
    /**
     * No-op for compatibility - register not needed for particle-only approach.
     */
    public static void register() {
        // Particle-only approach, no world render registration needed
    }
    
    /**
     * Call every tick to render visual markers.
     */
    public static void tick(MinecraftClient client) {
        if (client == null || client.world == null || client.player == null) return;
        
        // Only show visuals when TradeRun is active
        if (!TradeRunRuntime.get().isActive()) return;
        
        long now = System.currentTimeMillis();
        if (now - lastTickMs < TICK_INTERVAL_MS) return;
        lastTickMs = now;
        
        int playerY = client.player.getBlockPos().getY();
        
        // Render storage markers
        renderStorageMarkers(client, playerY);
        
        // Render villager cooldown markers
        renderCooldownMarkers(client);
    }
    
    private static void renderStorageMarkers(MinecraftClient client, int playerY) {
        // INPUT chest - single green sparkle
        Optional<StorageRegistry.StoredLocation> inputLoc = StorageRegistry.getForY(StorageRegistry.Role.INPUT, playerY);
        if (inputLoc.isPresent()) {
            BlockPos pos = inputLoc.get().toBlockPos();
            double x = pos.getX() + 0.5;
            double y = pos.getY() + 1.2;
            double z = pos.getZ() + 0.5;
            client.world.addParticle(ParticleTypes.HAPPY_VILLAGER, x, y, z, 0, 0, 0);
        }
        
        // OUTPUT chest - single flame
        Optional<StorageRegistry.StoredLocation> outputLoc = StorageRegistry.getForY(StorageRegistry.Role.OUTPUT, playerY);
        if (outputLoc.isPresent()) {
            BlockPos pos = outputLoc.get().toBlockPos();
            double x = pos.getX() + 0.5;
            double y = pos.getY() + 1.2;
            double z = pos.getZ() + 0.5;
            client.world.addParticle(ParticleTypes.FLAME, x, y, z, 0, 0, 0);
        }
    }
    
    private static void renderCooldownMarkers(MinecraftClient client) {
        double range = 48.0;
        Box box = client.player.getBoundingBox().expand(range, range, range);
        
        for (VillagerEntity v : client.world.getEntitiesByClass(VillagerEntity.class, box, VillagerEntity::isAlive)) {
            if (!CooldownRegistry.isOnCooldown(v)) continue;
            
            double x = v.getX();
            double y = v.getY() + v.getHeight() + 0.5; // Above head
            double z = v.getZ();
            
            // Single bright END_ROD particle - very visible white/yellow glow
            // Stationary (no velocity) so it appears as a static marker
            client.world.addParticle(ParticleTypes.END_ROD, x, y, z, 0, 0, 0);
        }
    }
}
