package com.payangar.cauchemar.perception;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.pathfinder.PathComputationType;

/**
 * Small shared helpers for surface-relative perception and movement, used by both the flight to shadow
 * ({@code SeekShadow}) and the vantage-point search ({@code VantagePointFinder}). Kept dependency-light
 * (pure block and light reads, no entity coupling) so any climbing creature can reuse it.
 */
public final class SurfaceUtil {

    private SurfaceUtil() {
    }

    /**
     * Whether a climber can cling at {@code pos}: the cell is passable for land pathing and has at least
     * one solid neighbouring face to hold onto (floor, wall or ceiling). This is the shared definition of
     * a "clingable" cell for both shadow-seeking and observation points.
     */
    public static boolean canCling(LevelReader level, BlockPos pos) {
        if (!level.getBlockState(pos).isPathfindable(PathComputationType.LAND)) {
            return false;
        }
        for (Direction dir : Direction.values()) {
            BlockPos neighbour = pos.relative(dir);
            if (level.getBlockState(neighbour).isFaceSturdy(level, neighbour, dir.getOpposite())) {
                return true;
            }
        }
        return false;
    }

    /** The AI light reading (max of block and sky raw brightness) that biases behaviour toward shadow. */
    public static int rawLight(LevelReader level, BlockPos pos) {
        return level.getMaxLocalRawBrightness(pos);
    }
}
