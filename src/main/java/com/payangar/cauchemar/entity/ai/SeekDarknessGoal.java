package com.payangar.cauchemar.entity.ai;

import com.payangar.cauchemar.entity.MotherSpiderEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.PathComputationType;

import java.util.EnumSet;

/**
 * Makes the Mother Spider shun light, the behavioural core of its "lurks in shadow" pillar:
 * <ul>
 *     <li>standing in light above the threshold, it sprints to the nearest dark, standable spot;</li>
 *     <li>if no dark spot is reachable nearby, it panics, dashing erratically until it stumbles into
 *     darkness, at which point the goal ends and calm wandering resumes.</li>
 * </ul>
 *
 * <p>"Light" is the effective local brightness ({@link Level#getMaxLocalRawBrightness(BlockPos)}, so
 * it factors in the time of day); a "dark spot" is a block at or below {@code lightThreshold}.
 */
public class SeekDarknessGoal extends Goal {

    private final MotherSpiderEntity spider;
    private final double sprintSpeed;
    private final int lightThreshold;
    private final int searchRadius;

    private boolean panicking;
    private int repathCooldown;

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
        // Keep fleeing/panicking until we reach darkness (or the spider freezes into observe).
        return !this.spider.isObserving() && this.inLight();
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        this.spider.setMovementMode(MotherSpiderEntity.MovementMode.SPRINT);
        this.panicking = false;
        this.repathCooldown = 0;
        this.headForDarknessOrPanic();
    }

    @Override
    public void stop() {
        this.panicking = false;
        this.spider.getNavigation().stop();
        this.spider.setMovementMode(MotherSpiderEntity.MovementMode.WANDER);
    }

    @Override
    public void tick() {
        if (--this.repathCooldown <= 0 || this.spider.getNavigation().isDone()) {
            this.headForDarknessOrPanic();
        }
    }

    /** Each repath, head to the nearest dark spot if one exists, otherwise panic-dash at random. */
    private void headForDarknessOrPanic() {
        BlockPos dark = this.findNearestDark();
        if (dark != null) {
            this.panicking = false;
            this.spider.getNavigation().moveTo(dark.getX() + 0.5, dark.getY(), dark.getZ() + 0.5, this.sprintSpeed);
            this.repathCooldown = 20;
        } else {
            this.panicking = true;
            this.dashRandomly();
            this.repathCooldown = 10 + this.spider.getRandom().nextInt(10);
        }
    }

    /** Erratic dash toward a random nearby position (panic, no shadow in sight). */
    private void dashRandomly() {
        RandomSource rng = this.spider.getRandom();
        BlockPos base = this.spider.blockPosition();
        double x = base.getX() + 0.5 + (rng.nextInt(13) - 6);
        double y = base.getY() + (rng.nextInt(5) - 2);
        double z = base.getZ() + 0.5 + (rng.nextInt(13) - 6);
        this.spider.getNavigation().moveTo(x, y, z, this.sprintSpeed);
    }

    /** Nearest standable block (passable, with a solid floor) whose light is at/below the threshold. */
    private BlockPos findNearestDark() {
        Level level = this.spider.level();
        BlockPos origin = this.spider.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();

        BlockPos best = null;
        double bestDistSqr = Double.MAX_VALUE;

        for (int dx = -this.searchRadius; dx <= this.searchRadius; dx++) {
            for (int dz = -this.searchRadius; dz <= this.searchRadius; dz++) {
                for (int dy = -3; dy <= 3; dy++) {
                    cursor.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (this.lightAt(cursor) > this.lightThreshold) {
                        continue;
                    }
                    if (!this.isStandable(level, cursor)) {
                        continue;
                    }
                    double distSqr = origin.distSqr(cursor);
                    if (distSqr < bestDistSqr) {
                        bestDistSqr = distSqr;
                        best = cursor.immutable();
                    }
                }
            }
        }
        return best;
    }

    private boolean isStandable(Level level, BlockPos pos) {
        return level.getBlockState(pos).isPathfindable(PathComputationType.LAND)
                && level.getBlockState(pos.below()).isFaceSturdy(level, pos.below(), Direction.UP);
    }
}
