package com.payangar.cauchemar.perception;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Finds an observation point: a clingable cell, at a standoff distance from a disturbance, with a clear
 * line of sight to it, from which a cautious climber can watch the source without walking up to it. The
 * search is deliberately bounded (a fixed set of sampled directions, the line-of-sight raycast applied
 * last only to survivors) so it can run once per investigation without pathfinding-level cost.
 *
 * <p>Generic on purpose: it depends only on the level and on positions, not on any creature type, so the
 * perception layer stays reusable. The caller supplies the standoff band and reach (from the creature's
 * emotional state, via {@code InvestigationStyle}).
 */
public final class VantagePointFinder {

    /**
     * Sample directions from the disturbance toward candidate observation points: a horizontal ring plus
     * an elevated ring (~35 degrees up), so a climber tends to watch from the side or from above, which
     * is both a better vantage and on theme for a spider. Computed once.
     */
    private static final Vec3[] DIRECTIONS = buildDirections();

    /** How far above/below a sampled point we probe to snap onto a real clingable surface. */
    private static final int SNAP_VERTICAL_REACH = 3;
    /** Weight of the optional directional preference in the score (comparable to a few light levels). */
    private static final double DIRECTION_WEIGHT = 4.0;

    private VantagePointFinder() {
    }

    /** Vantage search with no directional preference (used by curious investigation). */
    public static Optional<BlockPos> find(ServerLevel level, BlockPos observerPos, BlockPos disturbance,
                                          int minStandoff, int maxStandoff, int searchRadius) {
        return find(level, observerPos, disturbance, minStandoff, maxStandoff, searchRadius, null);
    }

    /**
     * The best observation cell for {@code disturbance}, or empty if none is both clingable and has line
     * of sight within the standoff band and reach. Empty means "cannot observe from cover": the caller
     * should give up rather than walk onto the source.
     *
     * @param observerPos         where the creature is now (candidates must be within {@code searchRadius})
     * @param minStandoff         closest the creature is willing to observe from (blocks)
     * @param maxStandoff         farthest a useful vantage may be (blocks)
     * @param searchRadius        how far from the creature a vantage may be (reach bias, e.g. follow range)
     * @param preferredDirection  optional unit direction from the disturbance that vantages should lean
     *                            toward (e.g. away from a threat, so a fearful retreat backs off rather
     *                            than circles past it); {@code null} for no preference
     */
    public static Optional<BlockPos> find(ServerLevel level, BlockPos observerPos, BlockPos disturbance,
                                          int minStandoff, int maxStandoff, int searchRadius, Vec3 preferredDirection) {
        Vec3 disturbanceCenter = Vec3.atCenterOf(disturbance);
        double standoff = (minStandoff + maxStandoff) * 0.5;
        long searchRadiusSq = (long) searchRadius * searchRadius;

        BlockPos best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (Vec3 direction : DIRECTIONS) {
            BlockPos aim = disturbance.offset(
                    (int) Math.round(direction.x * standoff),
                    (int) Math.round(direction.y * standoff),
                    (int) Math.round(direction.z * standoff));

            BlockPos cell = snapToClingable(level, aim);
            if (cell == null) {
                continue;
            }
            double distance = Math.sqrt(cell.distSqr(disturbance));
            if (distance < minStandoff || distance > maxStandoff) {
                continue;
            }
            if (cell.distSqr(observerPos) > searchRadiusSq) {
                continue;
            }
            // Line of sight is the expensive test, so it runs last and only on cells that already passed
            // the cheap distance and reach filters.
            if (!Sight.hasLineOfSight(level, Vec3.atCenterOf(cell), disturbanceCenter)) {
                continue;
            }
            double score = score(level, cell, disturbance, distance, standoff, preferredDirection);
            if (score > bestScore) {
                bestScore = score;
                best = cell;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Snaps a sampled (often mid-air) point onto a nearby clingable surface by probing a short vertical
     * window around it: the sampled point itself if it clings, otherwise the closest floor below or
     * ceiling above. Bounded to a few block reads.
     */
    private static BlockPos snapToClingable(ServerLevel level, BlockPos aim) {
        if (SurfaceUtil.canCling(level, aim)) {
            return aim;
        }
        for (int offset = 1; offset <= SNAP_VERTICAL_REACH; offset++) {
            BlockPos below = aim.below(offset);
            if (SurfaceUtil.canCling(level, below)) {
                return below;
            }
            BlockPos above = aim.above(offset);
            if (SurfaceUtil.canCling(level, above)) {
                return above;
            }
        }
        return null;
    }

    /**
     * Ranks a valid vantage: darker is better (she lurks in shadow), a distance near the standoff sweet
     * spot is better (not too close, not too far), and watching from above the source is a small bonus
     * (a spider looking down). Pure comparison score, no units.
     */
    private static double score(ServerLevel level, BlockPos cell, BlockPos disturbance, double distance, double standoff, Vec3 preferredDirection) {
        double darknessScore = -SurfaceUtil.rawLight(level, cell);
        double distanceScore = -Math.abs(distance - standoff);
        double heightBonus = cell.getY() > disturbance.getY() ? 1.5 : 0.0;
        double directionBonus = 0.0;
        if (preferredDirection != null) {
            Vec3 fromDisturbance = Vec3.atCenterOf(cell).subtract(Vec3.atCenterOf(disturbance)).normalize();
            directionBonus = DIRECTION_WEIGHT * fromDisturbance.dot(preferredDirection);
        }
        return darknessScore + distanceScore + heightBonus + directionBonus;
    }

    private static Vec3[] buildDirections() {
        Vec3[] directions = new Vec3[16];
        int index = 0;
        for (int step = 0; step < 8; step++) {
            double yaw = step * (Math.PI / 4.0);
            double cos = Math.cos(yaw);
            double sin = Math.sin(yaw);
            directions[index++] = new Vec3(cos, 0.0, sin);
            // Elevated by ~35 degrees (cos 35 ~= 0.82, sin 35 ~= 0.57) for over-the-shoulder vantage.
            directions[index++] = new Vec3(cos * 0.82, 0.57, sin * 0.82).normalize();
        }
        return directions;
    }
}
