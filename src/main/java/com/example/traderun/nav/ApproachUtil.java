package com.example.traderun.nav;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Finds good "walkway" positions around a villager for trading.
 * 
 * Simple rules:
 * - Valid tile has floor below, not solid at feet/head, has line-of-sight to villager
 * - Uses villager's Y level for consistency
 */
public class ApproachUtil {

    /**
     * General "best" approach: any valid tile in a 5x5 around the villager.
     */
    public static BlockPos findBestApproach(MinecraftClient client, VillagerEntity villager) {
        return findBestApproachExcluding(client, villager, null);
    }

    /**
     * General "best" approach, excluding a given tile.
     */
    public static BlockPos findBestApproachExcluding(MinecraftClient client,
                                                     VillagerEntity villager,
                                                     BlockPos exclude) {
        if (client.player == null || client.world == null) {
            return null;
        }

        World world = client.world;
        Vec3d playerPos = client.player.getPos();
        BlockPos villagerPos = villager.getBlockPos();

        List<BlockPos> candidates = new ArrayList<>();

        // First try a 5x5 square at villager's Y level
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;

                BlockPos walkwayPos = villagerPos.add(dx, 0, dz);
                if (exclude != null && walkwayPos.equals(exclude)) {
                    continue;
                }
                if (isValidWalkway(world, villager, walkwayPos)) {
                    candidates.add(walkwayPos.toImmutable());
                }
            }
        }

        // If no candidates, try expanded 7x7 area and ±1 Y levels
        if (candidates.isEmpty()) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        if (dx == 0 && dz == 0 && dy == 0) continue;
                        // Skip positions already checked
                        if (dy == 0 && Math.abs(dx) <= 2 && Math.abs(dz) <= 2) continue;

                        BlockPos walkwayPos = villagerPos.add(dx, dy, dz);
                        if (exclude != null && walkwayPos.equals(exclude)) {
                            continue;
                        }
                        if (isValidWalkway(world, villager, walkwayPos)) {
                            candidates.add(walkwayPos.toImmutable());
                        }
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        return pickClosestToPlayer(candidates, playerPos);
    }

    /**
     * Straight-only approach: 2 blocks out in the 4 cardinal directions.
     * Falls back to 3 blocks if 2 blocks doesn't work.
     */
    public static BlockPos findBestStraightApproach(MinecraftClient client, VillagerEntity villager) {
        if (client.player == null || client.world == null) {
            return null;
        }

        World world = client.world;
        Vec3d playerPos = client.player.getPos();
        BlockPos villagerPos = villager.getBlockPos();

        List<BlockPos> candidates = new ArrayList<>();

        // Try 2 blocks out first
        for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
            BlockPos walkwayPos = villagerPos.offset(dir, 2);
            if (isValidWalkway(world, villager, walkwayPos)) {
                candidates.add(walkwayPos.toImmutable());
            }
        }

        // If nothing at 2 blocks, try 3 blocks and ±1 Y
        if (candidates.isEmpty()) {
            for (int dy = -1; dy <= 1; dy++) {
                for (Direction dir : new Direction[]{Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST}) {
                    BlockPos walkwayPos = villagerPos.offset(dir, 3).add(0, dy, 0);
                    if (isValidWalkway(world, villager, walkwayPos)) {
                        candidates.add(walkwayPos.toImmutable());
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        return pickClosestToPlayer(candidates, playerPos);
    }

    /**
     * A walkway tile is valid if:
     * - There is a floor block below (non-air).
     * - The tile itself is NOT a solid full block (and not a raised trading block).
     * - The block above (head space) is NOT a solid full block.
     * - There is line-of-sight to the villager.
     */
    private static boolean isValidWalkway(World world, VillagerEntity villager, BlockPos walkwayPos) {
        BlockPos belowWalkway = walkwayPos.down();
        BlockPos headPos = walkwayPos.up();

        BlockState floorState = world.getBlockState(belowWalkway);
        BlockState walkState = world.getBlockState(walkwayPos);
        BlockState headState = world.getBlockState(headPos);

        // Require some floor under walkway (any non-air)
        if (floorState.isAir()) {
            return false;
        }

        // Reject problematic partial blocks that cause navigation issues
        String blockId = net.minecraft.registry.Registries.BLOCK.getId(floorState.getBlock()).toString();
        if (blockId.contains("brewing_stand") || blockId.contains("cauldron") || 
            blockId.contains("bed") || blockId.contains("composter")) {
            return false;  // These blocks cause navigation problems in tight spaces
        }

        // Walkway must NOT be a solid full block
        if (walkState.isSolidBlock(world, walkwayPos)) {
            return false;
        }
        
        // Check if walkway has a block that's too tall (would cause stepping up)
        // Allow carpets, pressure plates (<0.1), but reject trading blocks (>0.3)
        if (!walkState.isAir()) {
            var shape = walkState.getCollisionShape(world, walkwayPos);
            if (!shape.isEmpty()) {
                double maxY = shape.getMax(net.minecraft.util.math.Direction.Axis.Y);
                if (maxY > 0.3) {
                    return false;  // Block is too tall - would step on it
                }
            }
        }

        // Head space must NOT be a solid block
        if (headState.isSolidBlock(world, headPos)) {
            return false;
        }

        // Require line-of-sight from walkway to villager
        return hasLineOfSight(world, villager, walkwayPos);
    }

    private static boolean hasLineOfSight(World world, VillagerEntity villager, BlockPos walkwayPos) {
        Vec3d start = new Vec3d(
                walkwayPos.getX() + 0.5,
                walkwayPos.getY() + 1.62,
                walkwayPos.getZ() + 0.5
        );

        double villagerEyeY = villager.getY() + villager.getStandingEyeHeight();
        Vec3d end = new Vec3d(
                villager.getX(),
                villagerEyeY,
                villager.getZ()
        );

        RaycastContext ctx = new RaycastContext(
                start,
                end,
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                villager
        );

        HitResult hit = world.raycast(ctx);
        return hit == null || hit.getType() == HitResult.Type.MISS;
    }

    private static BlockPos pickClosestToPlayer(List<BlockPos> candidates, Vec3d playerPos) {
        Optional<BlockPos> best = candidates.stream()
                .min(Comparator.comparingDouble(pos -> posDistSq(pos, playerPos)));
        return best.orElse(null);
    }

    private static double posDistSq(BlockPos pos, Vec3d playerPos) {
        double cx = pos.getX() + 0.5;
        double cy = pos.getY();
        double cz = pos.getZ() + 0.5;
        double dx = playerPos.x - cx;
        double dy = playerPos.y - cy;
        double dz = playerPos.z - cz;
        return dx * dx + dy * dy + dz * dz;
    }
}
