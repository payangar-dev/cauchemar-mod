package com.payangar.cauchemar.movement;

import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;

/**
 * The navigation-side bridge the common {@link Steering} layer needs from its host navigator.
 *
 * <p>{@link Steering} owns the profile-agnostic path-following skeleton, but the underlying plan, the
 * world/level, the node evaluator and the stuck-detection machinery all live on the {@code
 * PathNavigation}. These bridges expose exactly what the steering reads, so the steering carries no
 * dependency on a concrete navigator. The methods mirror {@code protected} navigation members.
 */
public interface SteeringHost {

    /** The current plan being followed (the navigator's {@code path}); null when there is none. */
    Path steeringPath();

    /** The reference position used for waypoint reach tests (the navigator's {@code getTempMobPos}). */
    Vec3 steeringMobPos();

    /** Whether the entity may float, gating water/lava waypoints (the navigator's {@code canFloat}). */
    boolean steeringCanFloat();

    /** Whether a corner may be cut for the given node type (the navigator's {@code canCutCorner}). */
    boolean steeringCanCutCorner(PathType type);

    /** Runs the navigator's obstruction-based stuck detection for this tick. */
    void steeringStuckDetection(Vec3 pos);

    /** The active speed multiplier set by the moving goal (the navigator's {@code speedModifier}). */
    double steeringSpeedModifier();

    /** The level the navigator plans in. */
    Level steeringLevel();

    /** The node evaluator backing the plan (used by the direct-path funnel). */
    NodeEvaluator steeringNodeEvaluator();
}
