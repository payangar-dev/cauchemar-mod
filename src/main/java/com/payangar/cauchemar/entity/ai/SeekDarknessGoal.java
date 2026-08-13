package com.payangar.cauchemar.entity.ai;

import com.payangar.cauchemar.entity.MotherSpiderEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathComputationType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Makes the Mother Spider shun light, the behavioural core of its "lurks in shadow" pillar.
 *
 * <p>The objective is to reach a fully dark cell (light at or below {@code lightThreshold}, normally
 * 0). Rather than scanning a small cube and panicking when nothing is in range, it runs a directed
 * search: a flood over the cells it can <em>cling to</em> reachable from it (graph distance, not
 * straight-line), bounded by a budget, that <em>minimises light</em>:
 * <ul>
 *     <li>if a fully dark cell is reachable, it heads to the nearest one (the objective);</li>
 *     <li>otherwise it relocates with an agitated dash toward the darkest spot it can actually path
 *     to, then searches again from there, sinking toward darkness instead of settling in dim light.</li>
 * </ul>
 *
 * <p>While it is exposed (still in light) it never settles: if the darkest cling-able cell the flood
 * found is not navigable, or there is nothing darker nearby, it does not freeze retrying an impossible
 * path. It samples reachable destinations and dashes to the darkest one that the path finder confirms
 * it can reach (validated by {@code moveTo}'s return), so it keeps fleeing and the relocation breaks it
 * out of local light minima.
 *
 * <p>Because the spider climbs, "where it can cling" is any passable cell touching at least one solid
 * face (floor, wall or ceiling), and the flood walks those cells in 3D along the surfaces, so it finds
 * shadow on walls and ceilings too, not only on the ground. "Light" is the effective local brightness
 * ({@link Level#getMaxLocalRawBrightness(BlockPos)}, so it factors in the time of day).
 */
public class SeekDarknessGoal extends Goal {

    /** Cap on cells visited per search, so the flood stays bounded regardless of the radius. */
    private static final int MAX_SEARCH_NODES = 4096;
    /** Candidate destinations sampled for the agitated escape when no dark cell is navigable. */
    private static final int ESCAPE_SAMPLES = 8;
    /** Re-evaluation cadence (ticks) after a successful dash. */
    private static final int REPATH_TICKS = 20;
    /** Re-evaluation cadence (ticks) when boxed in: nothing reachable, so re-search rarely. */
    private static final int STUCK_REPATH_TICKS = 30;

    private final MotherSpiderEntity spider;
    private final double sprintSpeed;
    private final int lightThreshold;
    private final int searchRadius;

    private int repathCooldown;
    /** True while a dash has been issued and we are waiting to arrive (so arrival re-paths promptly). */
    private boolean expectingArrival;

    public SeekDarknessGoal(MotherSpiderEntity spider, double sprintSpeed, int lightThreshold, int searchRadius) {
        this.spider = spider;
        this.sprintSpeed = sprintSpeed;
        this.lightThreshold = lightThreshold;
        this.searchRadius = searchRadius;
        this.setFlags(EnumSet.of(Goal.Flag.MOVE));
    }

    private int lightAt(BlockPos pos) {
        return this.spider.level().getMaxLocalRawBrightness(pos);
    }

    private boolean inLight() {
        return this.lightAt(this.spider.blockPosition()) > this.lightThreshold;
    }

    @Override
    public boolean canUse() {
        return !this.spider.isObserving() && this.inLight();
    }

    @Override
    public boolean canContinueToUse() {
        // Keep seeking until the objective is reached (light <= threshold) or the spider freezes.
        return !this.spider.isObserving() && this.inLight();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.spider.setMovementMode(MotherSpiderEntity.MovementMode.SPRINT);
        this.repathCooldown = 0;
        this.expectingArrival = false;
        this.headForDarkness();
    }

    @Override
    public void stop() {
        this.spider.getNavigation().stop();
        this.spider.setMovementMode(MotherSpiderEntity.MovementMode.WANDER);
    }

    @Override
    public void tick() {
        // Re-evaluate on a cadence, and promptly when a dash we issued finishes. Crucially, do NOT
        // re-path every tick just because the navigation is idle: when boxed in (no path anywhere) a
        // bare isDone() trigger would re-flood the search every tick and lock the MOVE flag forever.
        boolean due = --this.repathCooldown <= 0;
        boolean arrived = this.expectingArrival && this.spider.getNavigation().isDone();
        if (due || arrived) {
            this.headForDarkness();
        }
    }

    private void headForDarkness() {
        BlockPos target = this.searchDarkness();
        if (target != null && this.dashTo(target)) {
            this.repathCooldown = REPATH_TICKS;
            this.expectingArrival = true;
            return;
        }

        // Either nothing darker is reachable, or the darkest cling-able cell is not navigable: never
        // settle while exposed, make a real reachable move that flees the light (and breaks the local
        // minimum by relocating).
        if (this.agitatedEscape()) {
            this.repathCooldown = REPATH_TICKS;
            this.expectingArrival = true;
            return;
        }

        // Boxed in: no path anywhere. Throttle so we don't re-flood the search every tick.
        this.repathCooldown = STUCK_REPATH_TICKS;
        this.expectingArrival = false;
    }

    private boolean dashTo(BlockPos cell) {
        return this.spider.getNavigation().moveTo(cell.getX() + 0.5, cell.getY(), cell.getZ() + 0.5, this.sprintSpeed);
    }

    /**
     * Directed search for darkness: a budgeted flood over the reachable cells the spider can cling to
     * (floor, walls and ceilings, in 3D). Returns the nearest fully dark cell if one exists, otherwise
     * the darkest reachable cell strictly darker than the current position, otherwise {@code null}.
     */
    private BlockPos searchDarkness() {
        Level level = this.spider.level();
        BlockPos origin = this.spider.blockPosition();
        int currentLight = this.lightAt(origin);

        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        queue.add(origin);
        visited.add(origin.asLong());

        BlockPos darkest = null;
        int darkestLight = currentLight; // only accept cells strictly darker than where we stand
        int budget = MAX_SEARCH_NODES;

        while (!queue.isEmpty() && budget-- > 0) {
            BlockPos cell = queue.poll();
            int light = this.lightAt(cell);

            if (light <= this.lightThreshold) {
                // Objective reached: BFS visits in graph-distance order, so this is the nearest dark cell.
                return cell;
            }
            if (light < darkestLight) {
                darkestLight = light;
                darkest = cell;
            }

            // Walk the surfaces in 3D: any of the six neighbours the spider can cling to.
            for (Direction dir : Direction.values()) {
                BlockPos next = cell.relative(dir);
                if (Math.abs(next.getX() - origin.getX()) > this.searchRadius
                        || Math.abs(next.getY() - origin.getY()) > this.searchRadius
                        || Math.abs(next.getZ() - origin.getZ()) > this.searchRadius) {
                    continue;
                }
                if (visited.add(next.asLong()) && this.canCling(level, next)) {
                    queue.add(next);
                }
            }
        }
        return darkest;
    }

    /**
     * An agitated relocation when no dark cell is navigable: sample destinations around the spider (in
     * 3D, since it climbs), then dash to the darkest one the path finder confirms it can reach. Returns
     * {@code true} if a dash was started; {@code false} only when nothing at all is reachable.
     */
    private boolean agitatedEscape() {
        BlockPos base = this.spider.blockPosition();
        RandomSource rng = this.spider.getRandom();

        List<BlockPos> candidates = new ArrayList<>(ESCAPE_SAMPLES);
        for (int i = 0; i < ESCAPE_SAMPLES; i++) {
            double angle = rng.nextDouble() * Math.PI * 2.0;
            int dist = 4 + rng.nextInt(Math.max(1, this.searchRadius));
            int dy = rng.nextInt(9) - 4;
            candidates.add(new BlockPos(
                    base.getX() + (int) Math.round(Math.cos(angle) * dist),
                    base.getY() + dy,
                    base.getZ() + (int) Math.round(Math.sin(angle) * dist)));
        }

        // Darkest first, so the spider commits to the most shadowed destination it can actually reach.
        candidates.sort(Comparator.comparingInt(this::lightAt));

        for (BlockPos c : candidates) {
            if (this.dashTo(c)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether the spider can cling at {@code pos}: the cell is passable and at least one of its six
     * neighbours presents a solid face toward it (floor, wall or ceiling). The plain floor case is
     * just the neighbour-below variant, so this generalises a ground "standable" test to surfaces.
     */
    private boolean canCling(Level level, BlockPos pos) {
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
}
