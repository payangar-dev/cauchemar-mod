package com.payangar.cauchemar.movement.climber;

import com.google.common.collect.ImmutableSet;
import com.payangar.cauchemar.movement.Steering;
import com.payangar.cauchemar.movement.SteeringHost;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.DebugPackets;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * The climbing navigator: it lets the path start off the ground and configures the node processor to
 * place nodes on walls and ceilings. Following the planned path (waypoint advance, look-ahead, the
 * direct-path funnel and stuck detection) is done by the common {@link Steering} layer, which this
 * navigator hosts through {@link SteeringHost}; the navigator itself owns the plan (A* + recompute).
 * This is what makes the spider deliberately path across walls/ceilings rather than only climbing on
 * contact. Ported from Nyf's Spiders.
 */
public class AdvancedClimberPathNavigator<T extends Mob & IClimberEntity> extends AdvancedGroundPathNavigator<T> implements SteeringHost {
    protected final IClimberEntity climber;

    protected final Steering<T> steering;

    public AdvancedClimberPathNavigator(T entity, Level worldIn, boolean checkObstructions, boolean canPathWalls, boolean canPathCeiling) {
        super(entity, worldIn, checkObstructions);

        this.climber = entity;

        if (this.nodeEvaluator instanceof AdvancedWalkNodeProcessor) {
            AdvancedWalkNodeProcessor processor = (AdvancedWalkNodeProcessor) this.nodeEvaluator;
            processor.setStartPathOnGround(false);
            processor.setCanPathWalls(canPathWalls);
            processor.setCanPathCeiling(canPathCeiling);
        }

        this.steering = new Steering<>(entity, this);
    }

    @Override
    protected Vec3 getTempMobPos() {
        return this.mob.position().add(0, this.mob.getBbHeight() / 2.0f, 0);
    }

    @Override
    protected boolean canUpdatePath() {
        // A climber is frequently between surfaces (rounding a step or corner) where onGround is
        // briefly false. Unlike a ground mob it must keep following its path during those micro-
        // detachments, otherwise it freezes on a stale waypoint mid-transition (the "moves but does
        // not advance" stall on stairs). Allow path following while attached to a climbing surface.
        return super.canUpdatePath() || this.climber.isAttachedToSurface();
    }

    @Override
    @Nullable
    public Path createPath(BlockPos pos, int checkpointRange) {
        return this.createPath(ImmutableSet.of(pos), 8, false, checkpointRange);
    }

    @Override
    @Nullable
    public Path createPath(Entity entityIn, int checkpointRange) {
        return this.createPath(ImmutableSet.of(entityIn.blockPosition()), 16, true, checkpointRange);
    }

    @Override
    public void tick() {
        ++this.tick;

        if (this.hasDelayedRecomputation) {
            this.recomputePath();
        }

        if (!this.isDone()) {
            if (this.canUpdatePath()) {
                this.steering.followPath();
            } else if (this.path != null && !this.path.isDone()) {
                Vec3 pos = this.getTempMobPos();
                Vec3 targetPos = this.path.getNextEntityPos(this.mob);

                if (pos.y > targetPos.y && !this.mob.onGround() && Mth.floor(pos.x) == Mth.floor(targetPos.x) && Mth.floor(pos.z) == Mth.floor(targetPos.z)) {
                    this.path.advance();
                }
            }

            DebugPackets.sendPathFindingPacket(this.level, this.mob, this.path, this.steering.getMaxDistanceToWaypoint());
        }

        // Drive toward the current target, or stand still when there is no path. Runs every tick: the
        // MoveControl is now a no-op, so it no longer zeroes the forward input when the mob arrives.
        this.steering.drive();
    }

    // ===================== SteeringHost (bridges to the navigator's plan/level/stuck machinery) =====================

    @Override
    public Path steeringPath() {
        return this.path;
    }

    @Override
    public Vec3 steeringMobPos() {
        return this.getTempMobPos();
    }

    @Override
    public boolean steeringCanFloat() {
        return this.canFloat();
    }

    @Override
    public boolean steeringCanCutCorner(PathType type) {
        return this.canCutCorner(type);
    }

    @Override
    public void steeringStuckDetection(Vec3 pos) {
        this.doStuckDetection(pos);
    }

    @Override
    public double steeringSpeedModifier() {
        return this.speedModifier;
    }

    @Override
    public Level steeringLevel() {
        return this.level;
    }

    @Override
    public NodeEvaluator steeringNodeEvaluator() {
        return this.getNodeEvaluator();
    }
}
