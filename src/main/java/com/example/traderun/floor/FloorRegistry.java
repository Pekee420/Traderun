package com.example.traderun.floor;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.village.VillagerProfession;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * Tracks which floors have which professions.
 * Stores clustered villager positions for navigation.
 */
public final class FloorRegistry {

    private static final double SCAN_RADIUS = 48.0;
    private static final double CLUSTER_DISTANCE = 5.0;
    private static final double CLUSTER_DISTANCE_SQ = CLUSTER_DISTANCE * CLUSTER_DISTANCE;

    public static final class FloorInfo {
        public String name;  // Optional custom name for the floor
        public int y;
        public Set<String> professions = new HashSet<>();
        public int villagerCount;
        public int clusterX; // Center of cluster
        public int clusterZ;
        
        /** Get display name - custom name if set, otherwise "Y=<y>" */
        public String getDisplayName() {
            return (name != null && !name.isEmpty()) ? name : "Y=" + y;
        }
    }
    
    public static final class TransitionPoint {
        public int fromY;
        public int toY;
        public int x;
        public int z;
        public String direction; // "up" or "down"
        
        public TransitionPoint() {}
        public TransitionPoint(int fromY, int toY, int x, int z) {
            this.fromY = fromY;
            this.toY = toY;
            this.x = x;
            this.z = z;
            this.direction = toY > fromY ? "up" : "down";
        }
    }
    
    private static final List<TransitionPoint> TRANSITIONS = new ArrayList<>();

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type FLOORS_TYPE = new TypeToken<List<FloorInfo>>(){}.getType();
    private static final Type TRANS_TYPE = new TypeToken<List<TransitionPoint>>(){}.getType();
    private static final Map<Integer, FloorInfo> FLOORS = new HashMap<>();

    private FloorRegistry() {}

    private static Path configPath() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("traderun");
        try { Files.createDirectories(dir); } catch (IOException ignored) {}
        return dir.resolve("floors.json");
    }
    
    private static Path transitionsPath() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("traderun");
        try { Files.createDirectories(dir); } catch (IOException ignored) {}
        return dir.resolve("transitions.json");
    }

    static { load(); }

    public static synchronized void load() {
        FLOORS.clear();
        TRANSITIONS.clear();
        
        // Load floors
        Path p = configPath();
        if (Files.exists(p)) {
            try (Reader r = Files.newBufferedReader(p)) {
                List<FloorInfo> list = GSON.fromJson(r, FLOORS_TYPE);
                if (list != null) {
                    for (FloorInfo f : list) {
                        if (f != null) FLOORS.put(f.y, f);
                    }
                }
            } catch (Throwable ignored) {}
        }
        
        // Load transitions
        Path t = transitionsPath();
        if (Files.exists(t)) {
            try (Reader r = Files.newBufferedReader(t)) {
                List<TransitionPoint> list = GSON.fromJson(r, TRANS_TYPE);
                if (list != null) {
                    TRANSITIONS.addAll(list);
                }
            } catch (Throwable ignored) {}
        }
    }

    private static synchronized void save() {
        // Save floors
        Path p = configPath();
        List<FloorInfo> list = new ArrayList<>(FLOORS.values());
        list.sort(Comparator.comparingInt(a -> a.y));
        try (Writer w = Files.newBufferedWriter(p)) {
            GSON.toJson(list, FLOORS_TYPE, w);
        } catch (Throwable ignored) {}
        
        // Save transitions
        Path t = transitionsPath();
        try (Writer w = Files.newBufferedWriter(t)) {
            GSON.toJson(TRANSITIONS, TRANS_TYPE, w);
        } catch (Throwable ignored) {}
    }

    /**
     * Scan current floor for villagers of given profession.
     * Only counts villagers in clusters (within 5 blocks of each other).
     * 
     * @return ScanResult with count and cluster center
     */
    public static ScanResult scanFloor(MinecraftClient client, String profession) {
        if (client == null || client.world == null || client.player == null) {
            return new ScanResult(0, null, "No world", null);
        }

        int playerY = client.player.getBlockPos().getY();
        Box box = client.player.getBoundingBox().expand(SCAN_RADIUS, 3, SCAN_RADIUS);

        Identifier targetProf = null;
        if (profession != null && !profession.isBlank()) {
            String raw = profession.trim();
            targetProf = Identifier.of(raw.contains(":") ? raw.toLowerCase() : ("minecraft:" + raw.toLowerCase()));
        }

        // Find all matching villagers on this floor
        List<VillagerEntity> villagers = new ArrayList<>();
        for (VillagerEntity v : client.world.getEntitiesByClass(VillagerEntity.class, box, VillagerEntity::isAlive)) {
            if (v.isBaby()) continue;
            
            int vy = v.getBlockPos().getY();
            if (Math.abs(vy - playerY) > 1) continue;

            if (targetProf != null) {
                VillagerProfession prof = v.getVillagerData().getProfession();
                Identifier id = Registries.VILLAGER_PROFESSION.getId(prof);
                if (id == null || !id.equals(targetProf)) continue;
            }

            villagers.add(v);
        }

        if (villagers.isEmpty()) {
            return new ScanResult(0, null, "No " + profession + " found on this floor", null);
        }

        // Find the largest cluster (villagers within 5 blocks of each other)
        List<VillagerEntity> largestCluster = findLargestCluster(villagers);

        if (largestCluster.isEmpty()) {
            return new ScanResult(0, null, "No clusters found", null);
        }

        // Calculate cluster center
        double cx = 0, cz = 0;
        for (VillagerEntity v : largestCluster) {
            cx += v.getX();
            cz += v.getZ();
        }
        cx /= largestCluster.size();
        cz /= largestCluster.size();

        BlockPos center = new BlockPos((int) cx, playerY, (int) cz);

        // Save to registry
        FloorInfo info = FLOORS.computeIfAbsent(playerY, y -> new FloorInfo());
        info.y = playerY;
        info.professions.add(profession.toLowerCase());
        info.villagerCount = largestCluster.size();
        info.clusterX = center.getX();
        info.clusterZ = center.getZ();
        save();

        return new ScanResult(largestCluster.size(), center, 
            "Found " + largestCluster.size() + " " + profession + " in cluster at Y=" + playerY, playerY);
    }

    private static List<VillagerEntity> findLargestCluster(List<VillagerEntity> villagers) {
        if (villagers.size() <= 1) return villagers;

        // Union-find to group villagers within CLUSTER_DISTANCE of each other
        int n = villagers.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) parent[i] = i;

        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double distSq = villagers.get(i).squaredDistanceTo(villagers.get(j));
                if (distSq <= CLUSTER_DISTANCE_SQ) {
                    union(parent, i, j);
                }
            }
        }

        // Count cluster sizes
        Map<Integer, List<VillagerEntity>> clusters = new HashMap<>();
        for (int i = 0; i < n; i++) {
            int root = find(parent, i);
            clusters.computeIfAbsent(root, k -> new ArrayList<>()).add(villagers.get(i));
        }

        // Return largest cluster
        return clusters.values().stream()
            .max(Comparator.comparingInt(List::size))
            .orElse(new ArrayList<>());
    }

    private static int find(int[] parent, int i) {
        if (parent[i] != i) parent[i] = find(parent, parent[i]);
        return parent[i];
    }

    private static void union(int[] parent, int i, int j) {
        int ri = find(parent, i);
        int rj = find(parent, j);
        if (ri != rj) parent[ri] = rj;
    }

    /**
     * Get the best floor to navigate to for given professions.
     * Returns floor with most villagers that isn't current floor.
     */
    public static Optional<FloorInfo> getBestFloorFor(Set<String> professions, int currentY) {
        FloorInfo best = null;
        int bestCount = 0;

        for (FloorInfo f : FLOORS.values()) {
            if (Math.abs(f.y - currentY) <= 1) continue; // Skip current floor

            // Check if this floor has any of the target professions
            boolean hasProf = false;
            for (String prof : professions) {
                if (f.professions.contains(prof.toLowerCase())) {
                    hasProf = true;
                    break;
                }
            }

            if (hasProf && f.villagerCount > bestCount) {
                bestCount = f.villagerCount;
                best = f;
            }
        }

        return Optional.ofNullable(best);
    }
    
    /**
     * Get ANY floor for given professions (including if already on it).
     * Used when starting to find where to go.
     */
    public static Optional<FloorInfo> getFloorForProfession(String profession) {
        if (profession == null) return Optional.empty();
        String profLower = profession.toLowerCase();
        
        for (FloorInfo f : FLOORS.values()) {
            if (f.professions.contains(profLower)) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }
    
    /**
     * Get floor by custom name OR profession (case-insensitive).
     * First tries to match by floor name, then by profession.
     */
    public static Optional<FloorInfo> getFloorByName(String name) {
        if (name == null || name.isEmpty()) return Optional.empty();
        String nameLower = name.toLowerCase();
        
        // First try to match by floor name
        for (FloorInfo f : FLOORS.values()) {
            if (f.name != null && f.name.toLowerCase().equals(nameLower)) {
                return Optional.of(f);
            }
        }
        
        // If no floor name match, try to match by profession
        for (FloorInfo f : FLOORS.values()) {
            if (f.professions != null && f.professions.contains(nameLower)) {
                return Optional.of(f);
            }
        }
        
        return Optional.empty();
    }
    
    /**
     * Get all floor names (excluding unnamed floors).
     */
    public static List<String> getAllFloorNames() {
        List<String> names = new ArrayList<>();
        for (FloorInfo f : FLOORS.values()) {
            if (f.name != null && !f.name.isEmpty()) {
                names.add(f.name);
            }
        }
        names.sort(String::compareToIgnoreCase);
        return names;
    }
    
    /**
     * Set or update floor name.
     */
    public static synchronized boolean setFloorName(int y, String name) {
        FloorInfo f = FLOORS.get(y);
        if (f == null) return false;
        f.name = name;
        save();
        return true;
    }

    public static synchronized void removeFloor(int y) {
        FLOORS.remove(y);
        save();
    }

    public static synchronized void clear() {
        FLOORS.clear();
        save();
    }

    public static synchronized List<FloorInfo> getAllFloors() {
        List<FloorInfo> list = new ArrayList<>(FLOORS.values());
        list.sort(Comparator.comparingInt(f -> f.y));
        return list;
    }
    
    /**
     * Check if any floors are registered.
     */
    public static boolean hasRegisteredFloors() {
        return !FLOORS.isEmpty();
    }
    
    /**
     * Check if player is on a registered floor (within ±1 Y).
     */
    public static boolean isOnRegisteredFloor(int playerY) {
        if (FLOORS.isEmpty()) return true; // No floors registered = anywhere is fine
        for (FloorInfo f : FLOORS.values()) {
            if (Math.abs(f.y - playerY) <= 1) return true;
        }
        return false;
    }
    
    /**
     * Get the nearest registered floor to return to.
     */
    public static Optional<FloorInfo> getNearestRegisteredFloor(int playerY) {
        if (FLOORS.isEmpty()) return Optional.empty();
        
        FloorInfo nearest = null;
        int nearestDist = Integer.MAX_VALUE;
        
        for (FloorInfo f : FLOORS.values()) {
            int dist = Math.abs(f.y - playerY);
            if (dist < nearestDist) {
                nearestDist = dist;
                nearest = f;
            }
        }
        
        return Optional.ofNullable(nearest);
    }
    
    // ---- Transition Points ----
    
    /**
     * Add a transition point at current player position.
     * Stand on stairs/ladder and specify target floor Y.
     */
    public static synchronized String addTransition(MinecraftClient client, int targetY) {
        if (client == null || client.player == null) return "No player";
        
        int currentY = client.player.getBlockPos().getY();
        int x = client.player.getBlockPos().getX();
        int z = client.player.getBlockPos().getZ();
        
        if (currentY == targetY) return "Target Y must be different from current Y";
        
        // Remove existing transition from this Y to target Y
        TRANSITIONS.removeIf(t -> t.fromY == currentY && t.toY == targetY);
        
        TransitionPoint tp = new TransitionPoint(currentY, targetY, x, z);
        TRANSITIONS.add(tp);
        save();
        
        String dir = targetY > currentY ? "up" : "down";
        return "Transition added: Y=" + currentY + " -> Y=" + targetY + " (" + dir + ") at " + x + ", " + z;
    }
    
    /**
     * Find transition from current floor to target floor.
     */
    public static synchronized Optional<TransitionPoint> findTransition(int fromY, int toY) {
        // Direct transition
        for (TransitionPoint t : TRANSITIONS) {
            if (Math.abs(t.fromY - fromY) <= 1 && Math.abs(t.toY - toY) <= 1) {
                return Optional.of(t);
            }
        }
        
        // Try to find any transition that gets us closer
        for (TransitionPoint t : TRANSITIONS) {
            if (Math.abs(t.fromY - fromY) <= 1) {
                // This transition starts from our floor
                // Check if it gets us closer to target
                int currentDist = Math.abs(toY - fromY);
                int newDist = Math.abs(toY - t.toY);
                if (newDist < currentDist) {
                    return Optional.of(t);
                }
            }
        }
        
        return Optional.empty();
    }
    
    public static synchronized List<TransitionPoint> getAllTransitions() {
        return new ArrayList<>(TRANSITIONS);
    }
    
    public static synchronized void clearTransitions() {
        TRANSITIONS.clear();
        save();
    }

    public static class ScanResult {
        public final int count;
        public final BlockPos clusterCenter;
        public final String message;
        public final Integer floorY;  // Y level of the floor that was created/updated

        public ScanResult(int count, BlockPos center, String message, Integer floorY) {
            this.count = count;
            this.clusterCenter = center;
            this.message = message;
            this.floorY = floorY;
        }
    }
}

