package com.example.traderun.villager;

import com.example.traderun.cooldown.CooldownRegistry;
import com.example.traderun.cooldown.RecentFailRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.village.VillagerProfession;

import java.util.*;

/**
 * Finds the nearest eligible villager.
 * Supports multiple professions and smart floor changing.
 */
public class VillagerFinder {

    private static final double SCAN_RADIUS = 48.0;
    private static final int MIN_VILLAGERS_FOR_FLOOR_CHANGE = 2;

    // Multiple professions support
    private Set<Identifier> targetProfessionIds = new HashSet<>();
    
    public void setTargetProfessionId(String professionId) {
        targetProfessionIds.clear();
        if (professionId == null || professionId.isBlank()) {
            return;
        }
        addProfession(professionId);
    }
    
    public void setTargetProfessions(List<String> professions) {
        targetProfessionIds.clear();
        if (professions == null) return;
        for (String prof : professions) {
            addProfession(prof);
        }
    }
    
    private void addProfession(String professionId) {
        if (professionId == null || professionId.isBlank()) return;
        try {
            String raw = professionId.trim();
            Identifier id = Identifier.of(raw.contains(":") ? raw.toLowerCase() : ("minecraft:" + raw.toLowerCase()));
            targetProfessionIds.add(id);
        } catch (Exception ignored) {}
    }
    
    public boolean hasMultipleProfessions() {
        return targetProfessionIds.size() > 1;
    }

    // Target floor Y - set by FSM to lock villager search to the correct floor
    private Integer targetFloorY = null;
    
    public void setTargetFloorY(Integer y) {
        this.targetFloorY = y;
    }
    
    public Integer getTargetFloorY() {
        return targetFloorY;
    }

    public Optional<VillagerEntity> findBestTarget(MinecraftClient client) {
        if (client == null || client.world == null || client.player == null) return Optional.empty();

        // Use target floor Y if set, otherwise fall back to player Y
        int floorY = (targetFloorY != null) ? targetFloorY : client.player.getBlockPos().getY();
        Box box = client.player.getBoundingBox().expand(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS);

        VillagerEntity bestSameFloor = null;
        double bestSameDist = Double.POSITIVE_INFINITY;

        for (VillagerEntity v : client.world.getEntitiesByClass(VillagerEntity.class, box, VillagerEntity::isAlive)) {
            if (!isEligible(v)) continue;

            // STRICT same floor - only villagers at target floor Y level (±1 for slabs/stairs)
            int vy = v.getBlockPos().getY();
            if (Math.abs(vy - floorY) > 1) continue;

            double d = v.squaredDistanceTo(client.player);
            if (d < bestSameDist) {
                bestSameDist = d;
                bestSameFloor = v;
            }
        }

        if (bestSameFloor != null) return Optional.of(bestSameFloor);
        return Optional.empty();
    }
    
    /**
     * Find the best floor to move to when current floor is exhausted.
     * Only returns a floor if:
     * 1. Multiple professions are selected
     * 2. That floor has 2+ eligible villagers
     * 
     * @return Y level of best floor, or empty if should stay on current floor
     */
    public Optional<Integer> findBestFloorToMoveTo(MinecraftClient client) {
        if (client == null || client.world == null || client.player == null) return Optional.empty();
        
        // Only consider floor changes if multiple professions are selected
        if (!hasMultipleProfessions()) {
            return Optional.empty();
        }

        int playerY = client.player.getBlockPos().getY();
        Box box = client.player.getBoundingBox().expand(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS);

        // Count eligible villagers per floor (Y level)
        Map<Integer, Integer> villagersPerFloor = new HashMap<>();
        Map<Integer, Double> closestDistPerFloor = new HashMap<>();

        for (VillagerEntity v : client.world.getEntitiesByClass(VillagerEntity.class, box, VillagerEntity::isAlive)) {
            if (!isEligible(v)) continue;

            int vy = v.getBlockPos().getY();
            
            // Skip current floor
            if (Math.abs(vy - playerY) <= 1) continue;

            villagersPerFloor.merge(vy, 1, Integer::sum);
            
            double dist = v.squaredDistanceTo(client.player);
            closestDistPerFloor.merge(vy, dist, Math::min);
        }

        // Find the best floor with 2+ villagers
        int bestFloor = -1;
        double bestDist = Double.POSITIVE_INFINITY;

        for (Map.Entry<Integer, Integer> entry : villagersPerFloor.entrySet()) {
            int floor = entry.getKey();
            int count = entry.getValue();
            
            if (count >= MIN_VILLAGERS_FOR_FLOOR_CHANGE) {
                double dist = closestDistPerFloor.getOrDefault(floor, Double.POSITIVE_INFINITY);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestFloor = floor;
                }
            }
        }

        if (bestFloor >= 0) {
            return Optional.of(bestFloor);
        }
        return Optional.empty();
    }
    
    /**
     * Check if current floor is exhausted (no eligible villagers).
     */
    public boolean isCurrentFloorExhausted(MinecraftClient client) {
        return findBestTarget(client).isEmpty();
    }
    
    /**
     * Find any nearest villager regardless of profession, cooldown, or floor.
     * Used as a navigation waypoint when stuck trying to reach storage.
     */
    public Optional<VillagerEntity> findAnyNearestVillager(MinecraftClient client) {
        if (client == null || client.world == null || client.player == null) return Optional.empty();
        
        Box box = client.player.getBoundingBox().expand(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS);
        
        VillagerEntity nearest = null;
        double nearestDist = Double.POSITIVE_INFINITY;
        
        for (VillagerEntity v : client.world.getEntitiesByClass(VillagerEntity.class, box, VillagerEntity::isAlive)) {
            if (v.isBaby()) continue;
            
            double dist = client.player.squaredDistanceTo(v);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = v;
            }
        }
        
        return Optional.ofNullable(nearest);
    }
    
    private boolean isEligible(VillagerEntity v) {
        if (v.isBaby()) return false;
        if (v.isSleeping()) return false;
        if (!professionOk(v)) return false;
        if (CooldownRegistry.isOnCooldown(v)) return false;
        if (RecentFailRegistry.isSuppressed(v)) return false;
        if (hasCustomer(v)) return false;
        return true;
    }

    /**
     * Debug string: counts that explain why selection returns empty.
     * Now only counts SAME FLOOR villagers for the detailed stats.
     */
    public String debugCounts(MinecraftClient client) {
        if (client == null || client.world == null || client.player == null) return "no client/world/player";

        int floorY = (targetFloorY != null) ? targetFloorY : client.player.getBlockPos().getY();
        Box box = client.player.getBoundingBox().expand(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS);

        int total = 0;
        int sameFloor = 0;
        int profOk = 0;
        int notCooldown = 0;
        int notSuppressed = 0;
        int freeCustomer = 0;
        int otherFloors = 0;

        for (VillagerEntity v : client.world.getEntitiesByClass(VillagerEntity.class, box, VillagerEntity::isAlive)) {
            total++;
            if (v.isBaby()) continue;
            if (v.isSleeping()) continue;
            
            int vy = v.getBlockPos().getY();
            boolean onSameFloor = Math.abs(vy - floorY) <= 1;
            
            if (onSameFloor) sameFloor++;
            else {
                otherFloors++;
                continue;  // Skip non-same-floor for detailed counts
            }

            if (!professionOk(v)) continue;
            profOk++;

            if (CooldownRegistry.isOnCooldown(v)) continue;
            notCooldown++;

            if (RecentFailRegistry.isSuppressed(v)) continue;
            notSuppressed++;

            if (hasCustomer(v)) continue;
            freeCustomer++;
        }

        String profs = targetProfessionIds.isEmpty() ? "any" : 
            String.join(",", targetProfessionIds.stream().map(Identifier::getPath).toList());

        return "profs=[" + profs + "] Y=" + floorY + 
                " sameFloor=" + sameFloor +
                " prof=" + profOk +
                " !cd=" + notCooldown +
                " !fail=" + notSuppressed +
                " eligible=" + freeCustomer;
    }

    private boolean professionOk(VillagerEntity v) {
        // If no professions specified, accept all
        if (targetProfessionIds.isEmpty()) return true;
        
        VillagerProfession prof = v.getVillagerData().getProfession();
        Identifier id = Registries.VILLAGER_PROFESSION.getId(prof);
        
        // Match ANY of the selected professions
        return id != null && targetProfessionIds.contains(id);
    }

    private boolean hasCustomer(VillagerEntity v) {
        try {
            Object customer = VillagerEntity.class.getMethod("getCustomer").invoke(v);
            return customer != null;
        } catch (Throwable ignored) {
            return false;
        }
    }
    
    /**
     * Count all eligible villagers on current floor (regardless of cooldown).
     * Used to detect "all on cooldown" situation.
     */
    public int countAllVillagersOnFloor(MinecraftClient client) {
        if (client == null || client.world == null || client.player == null) return 0;

        int floorY = (targetFloorY != null) ? targetFloorY : client.player.getBlockPos().getY();
        Box box = client.player.getBoundingBox().expand(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS);
        int count = 0;

        for (VillagerEntity v : client.world.getEntitiesByClass(VillagerEntity.class, box, VillagerEntity::isAlive)) {
            if (v.isBaby()) continue;
            if (v.isSleeping()) continue;
            if (!professionOk(v)) continue;
            
            int vy = v.getBlockPos().getY();
            if (Math.abs(vy - floorY) > 1) continue;
            
            count++;
        }
        return count;
    }

    /**
     * Check if there is at least one villager on the current floor that is NOT on cooldown.
     * Ignores recent fail suppression so we don't switch floors prematurely.
     */
    public boolean hasVillagerWithoutCooldown(MinecraftClient client) {
        if (client == null || client.world == null || client.player == null) return false;

        int floorY = (targetFloorY != null) ? targetFloorY : client.player.getBlockPos().getY();
        Box box = client.player.getBoundingBox().expand(SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS);

        for (VillagerEntity v : client.world.getEntitiesByClass(VillagerEntity.class, box, VillagerEntity::isAlive)) {
            if (v.isBaby()) continue;
            if (v.isSleeping()) continue;
            if (!professionOk(v)) continue;

            int vy = v.getBlockPos().getY();
            if (Math.abs(vy - floorY) > 1) continue;

            if (!CooldownRegistry.isOnCooldown(v)) {
                return true;
            }
        }
        return false;
    }
}
