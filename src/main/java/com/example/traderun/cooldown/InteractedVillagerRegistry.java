package com.example.traderun.cooldown;

import net.minecraft.entity.passive.VillagerEntity;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks which villagers have been interacted with this session.
 */
public final class InteractedVillagerRegistry {

    private static final Set<UUID> INTERACTED = ConcurrentHashMap.newKeySet();

    private InteractedVillagerRegistry() {}

    public static void markInteracted(VillagerEntity villager) {
        if (villager == null) return;
        INTERACTED.add(villager.getUuid());
    }

    public static boolean hasInteracted(VillagerEntity villager) {
        if (villager == null) return false;
        return INTERACTED.contains(villager.getUuid());
    }

    public static void clear() {
        INTERACTED.clear();
    }

    public static int count() {
        return INTERACTED.size();
    }
}

