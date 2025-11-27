package com.example.traderun.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class StorageRegistry {

    public enum Role { INPUT, OUTPUT }

    public static final class StoredLocation {
        public int x;
        public int y;
        public int z;

        public StoredLocation() {}
        public StoredLocation(int x, int y, int z) { this.x = x; this.y = y; this.z = z; }

        public BlockPos toBlockPos() { return new BlockPos(x, y, z); }
    }

    private static final class RoleData {
        public StoredLocation block;
        public double[] openSpot;       // Vec3d [x,y,z]
        public String rememberedItem;   // Identifier string
    }

    private static final class FloorData {
        public int y;
        public RoleData input;
        public RoleData output;
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type LIST_TYPE = new TypeToken<List<FloorData>>(){}.getType();
    private static final Map<Integer, FloorData> FLOORS = new HashMap<>();

    private StorageRegistry() {}

    private static Path configPath() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("traderun");
        try { Files.createDirectories(dir); } catch (IOException ignored) {}
        return dir.resolve("storages.json");
    }

    static { load(); }

    public static synchronized void load() {
        FLOORS.clear();
        Path p = configPath();
        if (!Files.exists(p)) return;

        try (Reader r = Files.newBufferedReader(p)) {
            List<FloorData> list = GSON.fromJson(r, LIST_TYPE);
            if (list == null) return;
            for (FloorData f : list) {
                if (f == null) continue;
                FLOORS.put(f.y, f);
            }
        } catch (Throwable ignored) {}
    }

    private static synchronized void save() {
        Path p = configPath();
        List<FloorData> list = new ArrayList<>(FLOORS.values());
        list.sort(Comparator.comparingInt(a -> a.y));
        try (Writer w = Files.newBufferedWriter(p)) {
            GSON.toJson(list, LIST_TYPE, w);
        } catch (Throwable ignored) {}
    }

    private static FloorData floor(int y) {
        return FLOORS.computeIfAbsent(y, yy -> {
            FloorData f = new FloorData();
            f.y = yy;
            return f;
        });
    }

    private static RoleData roleData(FloorData f, Role role) {
        if (role == Role.INPUT) {
            if (f.input == null) f.input = new RoleData();
            return f.input;
        } else {
            if (f.output == null) f.output = new RoleData();
            return f.output;
        }
    }

    private static int playerFloorY(MinecraftClient client) {
        if (client == null || client.player == null) return 0;
        return client.player.getBlockPos().getY();
    }

    public static synchronized void setForPlayerFloor(Role role, MinecraftClient client, BlockPos pos) {
        if (pos == null) return;
        int y = playerFloorY(client);
        FloorData f = floor(y);
        RoleData rd = roleData(f, role);
        rd.block = new StoredLocation(pos.getX(), pos.getY(), pos.getZ());
        // Save player's current position as the opening spot
        if (client != null && client.player != null) {
            Vec3d playerPos = client.player.getPos();
            rd.openSpot = new double[]{playerPos.x, playerPos.y, playerPos.z};
        }
        save();
    }

    public static synchronized void deleteForPlayerFloor(Role role, MinecraftClient client) {
        int y = playerFloorY(client);
        FloorData f = FLOORS.get(y);
        if (f == null) return;

        if (role == Role.INPUT) f.input = null;
        else f.output = null;

        if (f.input == null && f.output == null) FLOORS.remove(y);
        save();
    }

    public static void setForPlayerFloor(Role role, BlockPos pos) {
        setForPlayerFloor(role, MinecraftClient.getInstance(), pos);
    }

    public static void deleteForPlayerFloor(Role role) {
        deleteForPlayerFloor(role, MinecraftClient.getInstance());
    }

    public static synchronized int count() {
        return count(Role.INPUT) + count(Role.OUTPUT);
    }

    public static synchronized int count(Role role) {
        int c = 0;
        for (FloorData f : FLOORS.values()) {
            RoleData rd = (role == Role.INPUT) ? f.input : f.output;
            if (rd != null && rd.block != null) c++;
        }
        return c;
    }
    
    /**
     * Get detailed info about all storages for display.
     * Returns a list of strings describing each storage entry.
     */
    public static synchronized java.util.List<String> getDetailedList() {
        java.util.List<String> result = new ArrayList<>();
        java.util.List<Integer> sortedYs = new ArrayList<>(FLOORS.keySet());
        sortedYs.sort(Integer::compare);
        
        for (int y : sortedYs) {
            FloorData f = FLOORS.get(y);
            if (f == null) continue;
            
            StringBuilder sb = new StringBuilder();
            sb.append("§eY=").append(y).append("§r: ");
            
            boolean hasAny = false;
            
            if (f.input != null && f.input.block != null) {
                sb.append("§aINPUT§r(").append(f.input.block.x).append(",").append(f.input.block.z).append(")");
                if (f.input.rememberedItem != null) {
                    String item = f.input.rememberedItem;
                    if (item.contains(":")) item = item.substring(item.indexOf(':') + 1);
                    sb.append(" §7[").append(item).append("]§r");
                }
                hasAny = true;
            }
            
            if (f.output != null && f.output.block != null) {
                if (hasAny) sb.append(" | ");
                sb.append("§cOUTPUT§r(").append(f.output.block.x).append(",").append(f.output.block.z).append(")");
                if (f.output.rememberedItem != null) {
                    String item = f.output.rememberedItem;
                    if (item.contains(":")) item = item.substring(item.indexOf(':') + 1);
                    sb.append(" §7[").append(item).append("]§r");
                }
                hasAny = true;
            }
            
            if (hasAny) {
                result.add(sb.toString());
            }
        }
        
        return result;
    }

    /**
     * Get storage for exact floor Y; else fallback within |ΔY|<=3 choosing closest.
     */
    public static synchronized Optional<StoredLocation> getForY(Role role, int y) {
        // First try exact match
        FloorData exact = FLOORS.get(y);
        if (exact != null) {
            RoleData rd = (role == Role.INPUT) ? exact.input : exact.output;
            if (rd != null && rd.block != null) return Optional.of(rd.block);
        }

        // Fallback: search nearby floors (±3 Y levels)
        FloorData best = null;
        int bestDy = Integer.MAX_VALUE;

        for (FloorData f : FLOORS.values()) {
            int dy = Math.abs(f.y - y);
            if (dy > 3) continue; // Allow up to 3 Y levels difference
            RoleData rd = (role == Role.INPUT) ? f.input : f.output;
            if (rd == null || rd.block == null) continue;
            if (dy < bestDy) {
                bestDy = dy;
                best = f;
            }
        }

        if (best == null) return Optional.empty();
        RoleData rd = (role == Role.INPUT) ? best.input : best.output;
        return (rd != null && rd.block != null) ? Optional.of(rd.block) : Optional.empty();
    }

    public static synchronized void setOpenSpot(Role role, int floorY, Vec3d spot) {
        if (spot == null) return;
        FloorData f = floor(floorY);
        RoleData rd = roleData(f, role);
        rd.openSpot = new double[]{spot.x, spot.y, spot.z};
        save();
    }

    public static synchronized Optional<Vec3d> getOpenSpot(Role role, int floorY) {
        FloorData f = FLOORS.get(floorY);
        if (f == null) return Optional.empty();
        RoleData rd = (role == Role.INPUT) ? f.input : f.output;
        if (rd == null || rd.openSpot == null || rd.openSpot.length < 3) return Optional.empty();
        return Optional.of(new Vec3d(rd.openSpot[0], rd.openSpot[1], rd.openSpot[2]));
    }

    /**
     * Only call this when you have a NON-NULL item id from the container.
     */
    public static synchronized void updateRememberedItem(Role role, int floorY, Identifier itemId) {
        if (itemId == null) return;
        FloorData f = floor(floorY);
        RoleData rd = roleData(f, role);
        rd.rememberedItem = itemId.toString();
        save();
    }
    
    /**
     * Clear the remembered item for a role at a floor Y level.
     */
    public static synchronized void clearRememberedItem(Role role, int floorY) {
        FloorData exact = FLOORS.get(floorY);
        if (exact != null) {
            RoleData rd = (role == Role.INPUT) ? exact.input : exact.output;
            if (rd != null) {
                rd.rememberedItem = null;
                save();
            }
        }
    }
    
    /**
     * Clear remembered items for all floors.
     */
    public static synchronized void clearAllRememberedItems() {
        for (FloorData f : FLOORS.values()) {
            if (f.input != null) f.input.rememberedItem = null;
            if (f.output != null) f.output.rememberedItem = null;
        }
        save();
    }

    public static synchronized Optional<Identifier> getRememberedItem(Role role, int floorY) {
        FloorData exact = FLOORS.get(floorY);
        if (exact != null) {
            RoleData rd = (role == Role.INPUT) ? exact.input : exact.output;
            Identifier id = parseId(rd == null ? null : rd.rememberedItem);
            if (id != null) return Optional.of(id);
        }

        FloorData best = null;
        int bestDy = Integer.MAX_VALUE;
        Identifier bestId = null;

        for (FloorData f : FLOORS.values()) {
            int dy = Math.abs(f.y - floorY);
            if (dy > 1) continue;
            RoleData rd = (role == Role.INPUT) ? f.input : f.output;
            Identifier id = parseId(rd == null ? null : rd.rememberedItem);
            if (id == null) continue;
            if (dy < bestDy) {
                bestDy = dy;
                best = f;
                bestId = id;
            }
        }

        return (best != null && bestId != null) ? Optional.of(bestId) : Optional.empty();
    }

    private static Identifier parseId(String s) {
        if (s == null || s.isBlank()) return null;
        try { return Identifier.of(s); } catch (Throwable t) { return null; }
    }
    
    /**
     * Check if a floor has both INPUT and OUTPUT storage configured.
     * Used to verify a floor is ready for trading before switching to it.
     */
    public static synchronized boolean hasStorageForFloor(int floorY) {
        return getForY(Role.INPUT, floorY).isPresent() && getForY(Role.OUTPUT, floorY).isPresent();
    }
    
    /**
     * Check if a floor has INPUT storage configured.
     */
    public static synchronized boolean hasInputForFloor(int floorY) {
        return getForY(Role.INPUT, floorY).isPresent();
    }
    
    /**
     * Check if a floor has OUTPUT storage configured.
     */
    public static synchronized boolean hasOutputForFloor(int floorY) {
        return getForY(Role.OUTPUT, floorY).isPresent();
    }
    
    /**
     * Get ANY floor Y that has the specified role storage configured.
     * Returns the Y level closest to the given reference Y, or any if all are far.
     * Used as fallback when no nearby storage exists.
     */
    public static synchronized Optional<Integer> getAnyFloorWithStorage(Role role, int referenceY) {
        Integer bestY = null;
        int bestDy = Integer.MAX_VALUE;
        
        for (FloorData f : FLOORS.values()) {
            RoleData rd = (role == Role.INPUT) ? f.input : f.output;
            if (rd == null || rd.block == null) continue;
            
            int dy = Math.abs(f.y - referenceY);
            if (dy < bestDy) {
                bestDy = dy;
                bestY = f.y;
            }
        }
        
        return Optional.ofNullable(bestY);
    }
    
    /**
     * Get ANY floor Y that has BOTH input and output storage configured.
     * Returns the Y level closest to the given reference Y.
     */
    public static synchronized Optional<Integer> getAnyFloorWithBothStorage(int referenceY) {
        Integer bestY = null;
        int bestDy = Integer.MAX_VALUE;
        
        for (FloorData f : FLOORS.values()) {
            if (f.input == null || f.input.block == null) continue;
            if (f.output == null || f.output.block == null) continue;
            
            int dy = Math.abs(f.y - referenceY);
            if (dy < bestDy) {
                bestDy = dy;
                bestY = f.y;
            }
        }
        
        return Optional.ofNullable(bestY);
    }
    
    /**
     * Get all floor Y levels that have storage configured.
     */
    public static synchronized List<Integer> getAllFloorsWithStorage() {
        List<Integer> result = new ArrayList<>();
        for (FloorData f : FLOORS.values()) {
            boolean hasInput = f.input != null && f.input.block != null;
            boolean hasOutput = f.output != null && f.output.block != null;
            if (hasInput || hasOutput) {
                result.add(f.y);
            }
        }
        result.sort(Integer::compareTo);
        return result;
    }
}

