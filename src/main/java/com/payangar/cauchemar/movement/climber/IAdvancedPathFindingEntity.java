package com.payangar.cauchemar.movement.climber;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.PathType;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/**
 * Contract the advanced (surface-aware) path finder reads from the entity it paths for: which side
 * it currently walks on, stuck-detection tuning, and the pathing-malus hooks that bias the A* search
 * toward climbable surfaces. Ported from Nyf's Spiders. All methods have sensible defaults so the
 * entity only overrides what it needs.
 */
public interface IAdvancedPathFindingEntity {
    /** The side on which the entity is currently walking. */
    default Direction getGroundSide() {
        return Direction.DOWN;
    }

    /** Called when the mob tries to move along the path but is obstructed. */
    void onPathingObstructed(Direction facing);

    /** How many ticks the mob can be stuck before the path is considered obstructed. */
    default int getMaxStuckCheckTicks() {
        return 40;
    }

    /** Pathing malus for building a bridge. */
    default float getBridgePathingMalus(Mob entity, BlockPos pos, @Nullable Node fallPathPoint) {
        return -1.0f;
    }

    /**
     * Pathing malus for the given {@link PathType} and block position. Negative values are avoided at
     * all cost. 0.0 has the highest priority (preferred over all others). A positive value adds that
     * much travel cost (higher = less preferred), which lengthens the path and reduces the maximum
     * reachable distance in blocks.
     */
    default float getPathingMalus(BlockGetter cache, Mob entity, PathType nodeType, BlockPos pos, Vec3i direction, Predicate<Direction> sides) {
        return entity.getPathfindingMalus(nodeType);
    }

    /** Called after the path finder has finished; can clear caches. */
    default void pathFinderCleanup() {

    }
}
