package com.payangar.cauchemar.movement;

import com.payangar.cauchemar.movement.climber.DirectionalPathPoint;
import com.payangar.cauchemar.movement.climber.IClimberEntity;
import com.payangar.cauchemar.movement.climber.Orientation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.pathfinder.Node;
import net.minecraft.world.level.pathfinder.NodeEvaluator;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.level.pathfinder.PathType;
import net.minecraft.world.level.pathfinder.PathfindingContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * The common Steering layer: turns a planned {@link Path} into per-tick movement by following it
 * (waypoint advance + multi-node look-ahead + a direct-path funnel + stuck detection), then driving
 * the move controller toward the exact surface target of the upcoming node.
 *
 * <p>This is the profile-agnostic skeleton lifted out of the climbing navigator (it used to live in
 * {@code AdvancedClimberPathNavigator}). The climber-specific surface frame is read through the mob's
 * {@link IClimberEntity} facet; the navigator's plan / level / stuck machinery is reached through
 * {@link SteeringHost}. Behaviour is identical to the previous in-navigator implementation.
 */
public class Steering<T extends Mob & IClimberEntity> {

    /** Within this distance (blocks) of the path end the spider eases off, so it does not stop abruptly. */
    private static final double ARRIVE_RADIUS = 2.0;
    /** Floor on the arrive speed factor, so it keeps creeping in and actually reaches the goal. */
    private static final double MIN_ARRIVE_FACTOR = 0.3;

    /** Zero intent: tells the profile to stand still (zeroes the forward input) when there is no path. */
    private static final MoveIntent STOP = new MoveIntent(Vec3.ZERO, 0.0, null);

    private final T mob;
    private final IClimberEntity climber;
    private final SteeringHost host;
    private final Level level;
    private final NodeEvaluator nodeEvaluator;

    private Path path;

    protected Direction verticalFacing = Direction.DOWN;

    // Steering (fluidity): re-enable the existing direct-path shortcutter (was disabled, tagged
    // //todo on canMoveDirectly). Reusing it rather than reinventing a funnel. To validate in-game:
    // paths should get more direct (fewer staircase hops) WITHOUT the spider cutting through corners
    // (the suspect part is canMoveDirectly ignoring entity size; low risk here since the hitbox
    // footprint is ~1x1, but watch for clipping/stuck at corners).
    protected boolean findDirectPathPoints = true;

    protected float maxDistanceToWaypoint;

    public Steering(T mob, SteeringHost host) {
        this.mob = mob;
        this.climber = mob;
        this.host = host;
        this.level = host.steeringLevel();
        this.nodeEvaluator = host.steeringNodeEvaluator();
    }

    /** The waypoint reach tolerance computed this tick; the navigator reads it for the debug packet. */
    public float getMaxDistanceToWaypoint() {
        return this.maxDistanceToWaypoint;
    }

    /**
     * Advances the followed waypoint: multi-node look-ahead (so it does not backtrack at multi-side
     * positions), then the direct-path funnel, then stuck detection. Was {@code followThePath}.
     */
    public void followPath() {
        this.path = this.host.steeringPath();

        Vec3 pos = this.host.steeringMobPos();

        this.maxDistanceToWaypoint = this.mob.getBbWidth() > 0.75F ? this.mob.getBbWidth() / 2.0F : 0.75F - this.mob.getBbWidth() / 2.0F;
        float maxDistanceToWaypointY = Math.max(1 /*required for e.g. slabs*/, this.mob.getBbHeight() > 0.75F ? this.mob.getBbHeight() / 2.0F : 0.75F - this.mob.getBbHeight() / 2.0F);

        int sizeX = Mth.ceil(this.mob.getBbWidth());
        int sizeY = Mth.ceil(this.mob.getBbHeight());
        int sizeZ = sizeX;

        Orientation orientation = this.climber.getOrientation();
        Vec3 upVector = orientation.getGlobal(this.mob.getYRot(), -90);

        this.verticalFacing = Direction.getNearest((float) upVector.x, (float) upVector.y, (float) upVector.z);

        //Look up to 4 nodes ahead so it doesn't backtrack on positions with multiple path sides when changing/updating path
        for (int i = 4; i >= 0; i--) {
            if (this.path.getNextNodeIndex() + i < this.path.getNodeCount()) {
                Node currentTarget = this.path.getNode(this.path.getNextNodeIndex() + i);

                double dx = Math.abs(currentTarget.x + (int) (this.mob.getBbWidth() + 1.0f) * 0.5f - this.mob.getX());
                double dy = Math.abs(currentTarget.y - this.mob.getY());
                double dz = Math.abs(currentTarget.z + (int) (this.mob.getBbWidth() + 1.0f) * 0.5f - this.mob.getZ());

                boolean isWaypointInReach = dx < this.maxDistanceToWaypoint && dy < maxDistanceToWaypointY && dz < this.maxDistanceToWaypoint;

                boolean isOnSameSideAsTarget = false;
                if (this.host.steeringCanFloat() && (currentTarget.type == PathType.WATER || currentTarget.type == PathType.WATER_BORDER || currentTarget.type == PathType.LAVA)) {
                    isOnSameSideAsTarget = true;
                } else if (currentTarget instanceof DirectionalPathPoint) {
                    Direction targetSide = ((DirectionalPathPoint) currentTarget).getPathSide();
                    isOnSameSideAsTarget = targetSide == null || this.climber.getGroundDirection().getLeft() == targetSide;
                } else {
                    isOnSameSideAsTarget = true;
                }

                if (isOnSameSideAsTarget && (isWaypointInReach || (i == 0 && this.host.steeringCanCutCorner(this.path.getNextNode().type) && this.isNextTargetInLine(pos, sizeX, sizeY, sizeZ, 1 + i)))) {
                    this.path.setNextNodeIndex(this.path.getNextNodeIndex() + 1 + i);
                    break;
                }
            }
        }

        if (this.findDirectPathPoints) {
            Direction.Axis verticalAxis = this.verticalFacing.getAxis();

            int firstDifferentHeightPoint = this.path.getNodeCount();

            switch (verticalAxis) {
                case X:
                    for (int i = this.path.getNextNodeIndex(); i < this.path.getNodeCount(); ++i) {
                        if (this.path.getNode(i).x != Math.floor(pos.x)) {
                            firstDifferentHeightPoint = i;
                            break;
                        }
                    }
                    break;
                case Y:
                    for (int i = this.path.getNextNodeIndex(); i < this.path.getNodeCount(); ++i) {
                        if (this.path.getNode(i).y != Math.floor(pos.y)) {
                            firstDifferentHeightPoint = i;
                            break;
                        }
                    }
                    break;
                case Z:
                    for (int i = this.path.getNextNodeIndex(); i < this.path.getNodeCount(); ++i) {
                        if (this.path.getNode(i).z != Math.floor(pos.z)) {
                            firstDifferentHeightPoint = i;
                            break;
                        }
                    }
                    break;
            }

            for (int i = firstDifferentHeightPoint - 1; i >= this.path.getNextNodeIndex(); --i) {
                if (this.canMoveDirectly(pos, this.path.getEntityPosAtNode(this.mob, i)/*, sizeX, sizeY, sizeZ*/)) {
                    this.path.setNextNodeIndex(i);
                    break;
                }
            }
        }

        this.host.steeringStuckDetection(pos);
    }

    /**
     * Steers toward the exact surface target of the upcoming node (with its face, when the node carries
     * one), easing off near the path end (arrive); or, when there is no path to follow, tells the
     * profile to stand still. The resulting {@link MoveIntent} is applied through the profile here, in
     * the navigation phase (the {@link com.payangar.cauchemar.movement.climber.ClimberMoveController}
     * is a no-op). Was the tail of {@code AdvancedClimberPathNavigator.tick()} plus the per-tick body
     * of the old move controller.
     */
    public void drive() {
        this.path = this.host.steeringPath();
        if (this.path == null || this.path.isDone()) {
            // Idle: the no-op MoveControl no longer zeroes the forward input on arrival, so the profile
            // must be told to stand still (matches the old MoveControl WAIT branch's setZza(0)).
            this.mob.applyMovement(STOP);
            return;
        }

        Node targetPoint = this.path.getNode(this.path.getNextNodeIndex());

        Direction pathSide = null;
        if (targetPoint instanceof DirectionalPathPoint) {
            pathSide = ((DirectionalPathPoint) targetPoint).getPathSide();
        }

        Direction dir = pathSide != null ? pathSide : Direction.DOWN;

        Vec3 targetPos = this.getExactPathingTarget(this.level, targetPoint.asBlockPos(), dir);

        double speed = this.mob.getMovementSpeed() * this.arriveSpeed(this.host.steeringSpeedModifier());

        if (pathSide != null) {
            this.applySteering(targetPos, targetPoint.asBlockPos().relative(pathSide), pathSide, speed);
        } else {
            this.applySteering(targetPos, null, null, speed);
        }
    }

    /**
     * Per-tick steering toward {@code wanted}: nudges the approach onto the exact face (when the node
     * carries one), decides the corner-rounding jump, projects the move onto the current surface plane,
     * and emits the {@link MoveIntent} the profile executes. The transition (jump) is a steering
     * decision carried by the intent; the profile only carries it out. Lifted verbatim from the old
     * {@code ClimberMoveController} MOVE_TO branch (climber-specific; it reads the surface frame).
     */
    private void applySteering(Vec3 wanted, BlockPos block, Direction side, double speed) {
        double dx = wanted.x - this.mob.getX();
        double dy = wanted.y - this.mob.getY();
        double dz = wanted.z - this.mob.getZ();

        if (side != null && block != null) {
            VoxelShape shape = this.mob.level().getBlockState(block).getCollisionShape(this.mob.level(), block);

            AABB aabb = this.mob.getBoundingBox();

            double ox = 0;
            double oy = 0;
            double oz = 0;

            switch (side) {
                case DOWN:
                    if (aabb.minY >= block.getY() + shape.max(Direction.Axis.Y) - 0.01D) ox -= 0.1D;
                    break;
                case UP:
                    if (aabb.maxY <= block.getY() + shape.min(Direction.Axis.Y) + 0.01D) oy += 0.1D;
                    break;
                case WEST:
                    if (aabb.minX >= block.getX() + shape.max(Direction.Axis.X) - 0.01D) ox -= 0.1D;
                    break;
                case EAST:
                    if (aabb.maxX <= block.getX() + shape.min(Direction.Axis.X) + 0.01D) ox += 0.1D;
                    break;
                case NORTH:
                    if (aabb.minZ >= block.getZ() + shape.max(Direction.Axis.Z) - 0.01D) oz -= 0.1D;
                    break;
                case SOUTH:
                    if (aabb.maxZ <= block.getZ() + shape.min(Direction.Axis.Z) + 0.01D) oz += 0.1D;
                    break;
            }

            AABB blockAabb = new AABB(block.relative(side.getOpposite()));

            if (aabb.intersects(blockAabb)) {
                Direction.Axis offsetAxis = side.getAxis();
                double offset = switch (offsetAxis) {
                    case X -> side.getStepX() * 0.5f;
                    case Y -> side.getStepY() * 0.5f;
                    case Z -> side.getStepZ() * 0.5f;
                };

                double allowedOffset = shape.collide(offsetAxis, aabb.move(-block.getX(), -block.getY(), -block.getZ()), offset);

                switch (side) {
                    case DOWN:
                        if (aabb.minY + allowedOffset < block.getY() + shape.max(Direction.Axis.Y) - 0.01D) oy = 0;
                        break;
                    case UP:
                        if (aabb.maxY + allowedOffset > block.getY() + shape.min(Direction.Axis.Y) + 0.01D) oy = 0;
                        break;
                    case WEST:
                        if (aabb.minX + allowedOffset < block.getX() + shape.max(Direction.Axis.X) - 0.01D) ox = 0;
                        break;
                    case EAST:
                        if (aabb.maxX + allowedOffset > block.getX() + shape.min(Direction.Axis.X) + 0.01D) ox = 0;
                        break;
                    case NORTH:
                        if (aabb.minZ + allowedOffset < block.getZ() + shape.max(Direction.Axis.Z) - 0.01D) oz = 0;
                        break;
                    case SOUTH:
                        if (aabb.maxZ + allowedOffset > block.getZ() + shape.min(Direction.Axis.Z) + 0.01D) oz = 0;
                        break;
                }
            }

            dx += ox;
            dy += oy;
            dz += oz;
        }

        Direction mainOffsetDir = Direction.getNearest(dx, dy, dz);

        float reach = switch (mainOffsetDir) {
            case DOWN -> 0;
            case UP -> this.mob.getBbHeight();
            default -> this.mob.getBbWidth() * 0.5f;
        };

        double verticalOffset = Math.abs(mainOffsetDir.getStepX() * dx) + Math.abs(mainOffsetDir.getStepY() * dy) + Math.abs(mainOffsetDir.getStepZ() * dz);

        Direction groundDir = this.climber.getGroundDirection().getLeft();

        Vec3 jumpDir = null;

        if (side != null && verticalOffset > reach - 0.05f && groundDir != side && groundDir.getAxis() != side.getAxis()) {
            double hdx = (1 - Math.abs(mainOffsetDir.getStepX())) * dx;
            double hdy = (1 - Math.abs(mainOffsetDir.getStepY())) * dy;
            double hdz = (1 - Math.abs(mainOffsetDir.getStepZ())) * dz;

            double hdsq = hdx * hdx + hdy * hdy + hdz * hdz;
            if (hdsq < 0.707f) {
                dx -= side.getStepX() * 0.2f;
                dy -= side.getStepY() * 0.2f;
                dz -= side.getStepZ() * 0.2f;

                if (hdsq < 0.1f) {
                    jumpDir = new Vec3(mainOffsetDir.getStepX(), mainOffsetDir.getStepY(), mainOffsetDir.getStepZ());
                }
            }
        }

        Orientation orientation = this.climber.getOrientation();
        Vec3 up = orientation.getGlobal(this.mob.getYRot(), -90);
        Vec3 offset = new Vec3(dx, dy, dz);
        Vec3 inPlaneMove = offset.subtract(up.scale(offset.dot(up)));
        double targetDist = inPlaneMove.length();

        if (jumpDir == null && side != null && targetDist >= 0.0001D && targetDist < 0.1D && groundDir == side.getOpposite()) {
            jumpDir = new Vec3(side.getStepX(), side.getStepY(), side.getStepZ());
        }

        this.mob.applyMovement(new MoveIntent(inPlaneMove, speed, jumpDir));
    }

    /**
     * Arrive behaviour: scales the commanded speed down as the spider closes on the path end, so it
     * eases into its goal instead of stopping dead (the MC-94054 overrun that would otherwise fight
     * this is disabled for the spider, so there is nothing to reconcile here). A floor keeps it
     * creeping in so it still reaches the goal and the navigation completes. Beyond {@link
     * #ARRIVE_RADIUS} the base speed is untouched, so intermediate waypoints are taken at full speed.
     */
    private double arriveSpeed(double baseSpeed) {
        int nodeCount = this.path.getNodeCount();
        if (nodeCount == 0) {
            return baseSpeed;
        }

        Vec3 goal = this.path.getEntityPosAtNode(this.mob, nodeCount - 1);
        double dist = this.mob.position().distanceTo(goal);
        if (dist >= ARRIVE_RADIUS) {
            return baseSpeed;
        }

        double factor = Math.max(MIN_ARRIVE_FACTOR, dist / ARRIVE_RADIUS);
        return baseSpeed * factor;
    }

    public Vec3 getExactPathingTarget(BlockGetter blockaccess, BlockPos pos, Direction dir) {
        BlockPos offsetPos = pos.relative(dir);

        VoxelShape shape = blockaccess.getBlockState(offsetPos).getCollisionShape(blockaccess, offsetPos);

        Direction.Axis axis = dir.getAxis();

        int sign = dir.getStepX() + dir.getStepY() + dir.getStepZ();
        double offset = shape.isEmpty() ? sign /*undo offset if no collider*/ : (sign > 0 ? shape.min(axis) - 1 : shape.max(axis));

        double marginXZ = 1 - (this.mob.getBbWidth() % 1);
        double marginY = 1 - (this.mob.getBbHeight() % 1);

        double pathingOffsetXZ = (int) (this.mob.getBbWidth() + 1.0F) * 0.5D;
        double pathingOffsetY = (int) (this.mob.getBbHeight() + 1.0F) * 0.5D - this.mob.getBbHeight() * 0.5f;

        double x = offsetPos.getX() + pathingOffsetXZ + dir.getStepX() * marginXZ;
        double y = offsetPos.getY() + pathingOffsetY + (dir == Direction.DOWN ? -pathingOffsetY : 0.0D) + (dir == Direction.UP ? -pathingOffsetY + marginY : 0.0D);
        double z = offsetPos.getZ() + pathingOffsetXZ + dir.getStepZ() * marginXZ;

        switch (axis) {
            default:
            case X:
                return new Vec3(x + offset, y, z);
            case Y:
                return new Vec3(x, y + offset, z);
            case Z:
                return new Vec3(x, y, z + offset);
        }
    }

    private boolean isNextTargetInLine(Vec3 pos, int sizeX, int sizeY, int sizeZ, int offset) {
        if (this.path.getNextNodeIndex() + offset >= this.path.getNodeCount()) {
            return false;
        } else {
            Vec3 currentTarget = Vec3.atBottomCenterOf(this.path.getNextNodePos());

            if (!pos.closerThan(currentTarget, 2.0D)) {
                return false;
            } else {
                Vec3 nextTarget = Vec3.atBottomCenterOf(this.path.getNodePos(this.path.getNextNodeIndex() + offset));
                Vec3 targetDir = nextTarget.subtract(currentTarget);
                Vec3 currentDir = pos.subtract(currentTarget);

                if (targetDir.dot(currentDir) > 0.0D) {
                    Direction.Axis ax, ay, az;
                    boolean invertY;

                    switch (this.verticalFacing.getAxis()) {
                        case X:
                            ax = Direction.Axis.Z;
                            ay = Direction.Axis.X;
                            az = Direction.Axis.Y;
                            invertY = this.verticalFacing.getStepX() < 0;
                            break;
                        default:
                        case Y:
                            ax = Direction.Axis.X;
                            ay = Direction.Axis.Y;
                            az = Direction.Axis.Z;
                            invertY = this.verticalFacing.getStepY() < 0;
                            break;
                        case Z:
                            ax = Direction.Axis.Y;
                            ay = Direction.Axis.Z;
                            az = Direction.Axis.X;
                            invertY = this.verticalFacing.getStepZ() < 0;
                            break;
                    }

                    //Make sure that the mob can stand at the next point in the same orientation it currently has
                    return this.isSafeToStandAt(Mth.floor(nextTarget.x), Mth.floor(nextTarget.y), Mth.floor(nextTarget.z), sizeX, sizeY, sizeZ, currentTarget, 0, 0, -1, ax, ay, az, invertY);
                }

                return false;
            }
        }
    }

    //todo: fix this?
    private boolean canMoveDirectly(Vec3 start, Vec3 end/*, int sizeX, int sizeY, int sizeZ*/) {
        int sizeX = 0;//(int) this.mob.getBbWidth();
        int sizeY = 0;//(int) this.mob.getBbHeight();
        int sizeZ = 0;//(int) this.mob.getBbWidth();
        switch (this.verticalFacing.getAxis()) {
            case X:
                return this.isDirectPathBetweenPoints(start, end, sizeX, sizeY, sizeZ, Direction.Axis.Z, Direction.Axis.X, Direction.Axis.Y, 0.0D, this.verticalFacing.getStepX() < 0);
            case Y:
                return this.isDirectPathBetweenPoints(start, end, sizeX, sizeY, sizeZ, Direction.Axis.X, Direction.Axis.Y, Direction.Axis.Z, 0.0D, this.verticalFacing.getStepY() < 0);
            case Z:
                return this.isDirectPathBetweenPoints(start, end, sizeX, sizeY, sizeZ, Direction.Axis.Y, Direction.Axis.Z, Direction.Axis.X, 0.0D, this.verticalFacing.getStepZ() < 0);
        }
        return false;
    }

    protected static double swizzle(Vec3 vec, Direction.Axis axis) {
        switch (axis) {
            case X:
                return vec.x;
            case Y:
                return vec.y;
            case Z:
                return vec.z;
        }
        return 0;
    }

    protected static int swizzle(int x, int y, int z, Direction.Axis axis) {
        switch (axis) {
            case X:
                return x;
            case Y:
                return y;
            case Z:
                return z;
        }
        return 0;
    }

    protected static int unswizzle(int x, int y, int z, Direction.Axis ax, Direction.Axis ay, Direction.Axis az, Direction.Axis axis) {
        Direction.Axis unswizzle;
        if (axis == ax) {
            unswizzle = Direction.Axis.X;
        } else if (axis == ay) {
            unswizzle = Direction.Axis.Y;
        } else {
            unswizzle = Direction.Axis.Z;
        }
        return swizzle(x, y, z, unswizzle);
    }

    protected boolean isDirectPathBetweenPoints(Vec3 start, Vec3 end, int sizeX, int sizeY, int sizeZ, Direction.Axis ax, Direction.Axis ay, Direction.Axis az, double minDotProduct, boolean invertY) {
        int bx = Mth.floor(swizzle(start, ax));
        int bz = Mth.floor(swizzle(start, az));
        double dx = swizzle(end, ax) - swizzle(start, ax);
        double dz = swizzle(end, az) - swizzle(start, az);
        double dSq = dx * dx + dz * dz;

        int by = (int) swizzle(start, ay);

        int sizeX2 = swizzle(sizeX, sizeY, sizeZ, ax);
        int sizeY2 = swizzle(sizeX, sizeY, sizeZ, ay);
        int sizeZ2 = swizzle(sizeX, sizeY, sizeZ, az);

        if (dSq < 1.0E-8D) {
            return false;
        } else {
            double d3 = 1.0D / Math.sqrt(dSq);
            dx = dx * d3;
            dz = dz * d3;
            sizeX2 = sizeX2 + 2;
            sizeZ2 = sizeZ2 + 2;

            if (!this.isSafeToStandAt(
                    unswizzle(bx, by, bz, ax, ay, az, Direction.Axis.X), unswizzle(bx, by, bz, ax, ay, az, Direction.Axis.Y), unswizzle(bx, by, bz, ax, ay, az, Direction.Axis.Z),
                    unswizzle(sizeX2, sizeY2, sizeZ2, ax, ay, az, Direction.Axis.X), unswizzle(sizeX2, sizeY2, sizeZ2, ax, ay, az, Direction.Axis.Y), unswizzle(sizeX2, sizeY2, sizeZ2, ax, ay, az, Direction.Axis.Z),
                    start, dx, dz, minDotProduct, ax, ay, az, invertY)) {
                return false;
            } else {
                sizeX2 = sizeX2 - 2;
                sizeZ2 = sizeZ2 - 2;
                double stepX = 1.0D / Math.abs(dx);
                double stepZ = 1.0D / Math.abs(dz);
                double relX = (double) bx - swizzle(start, ax);
                double relZ = (double) bz - swizzle(start, az);

                if (dx >= 0.0D) {
                    ++relX;
                }

                if (dz >= 0.0D) {
                    ++relZ;
                }

                relX = relX / dx;
                relZ = relZ / dz;
                int dirX = dx < 0.0D ? -1 : 1;
                int dirZ = dz < 0.0D ? -1 : 1;
                int ex = Mth.floor(swizzle(end, ax));
                int ez = Mth.floor(swizzle(end, az));
                int offsetX = ex - bx;
                int offsetZ = ez - bz;

                while (offsetX * dirX > 0 || offsetZ * dirZ > 0) {
                    if (relX < relZ) {
                        relX += stepX;
                        bx += dirX;
                        offsetX = ex - bx;
                    } else {
                        relZ += stepZ;
                        bz += dirZ;
                        offsetZ = ez - bz;
                    }

                    if (!this.isSafeToStandAt(
                            unswizzle(bx, by, bz, ax, ay, az, Direction.Axis.X), unswizzle(bx, by, bz, ax, ay, az, Direction.Axis.Y), unswizzle(bx, by, bz, ax, ay, az, Direction.Axis.Z),
                            unswizzle(sizeX2, sizeY2, sizeZ2, ax, ay, az, Direction.Axis.X), unswizzle(sizeX2, sizeY2, sizeZ2, ax, ay, az, Direction.Axis.Y), unswizzle(sizeX2, sizeY2, sizeZ2, ax, ay, az, Direction.Axis.Z),
                            start, dx, dz, minDotProduct, ax, ay, az, invertY)) {
                        return false;
                    }
                }

                return true;
            }
        }
    }

    protected boolean isSafeToStandAt(int x, int y, int z, int sizeX, int sizeY, int sizeZ, Vec3 start, double dx, double dz, double minDotProduct, Direction.Axis ax, Direction.Axis ay, Direction.Axis az, boolean invertY) {
        int sizeX2 = swizzle(sizeX, sizeY, sizeZ, ax);
        int sizeZ2 = swizzle(sizeX, sizeY, sizeZ, az);

        int bx = swizzle(x, y, z, ax) - sizeX2 / 2;
        int bz = swizzle(x, y, z, az) - sizeZ2 / 2;

        int by = swizzle(x, y, z, ay);

        if (!this.isPositionClear(
                unswizzle(bx, y, bz, ax, ay, az, Direction.Axis.X), unswizzle(bx, y, bz, ax, ay, az, Direction.Axis.Y), unswizzle(bx, y, bz, ax, ay, az, Direction.Axis.Z),
                sizeX, sizeY, sizeZ, start, dx, dz, minDotProduct, ax, ay, az)) {
            return false;
        } else {
            for (int obx = bx; obx < bx + sizeX2; ++obx) {
                for (int obz = bz; obz < bz + sizeZ2; ++obz) {
                    double offsetX = (double) obx + 0.5D - swizzle(start, ax);
                    double offsetZ = (double) obz + 0.5D - swizzle(start, az);

                    if (offsetX * dx + offsetZ * dz >= minDotProduct) {
                        PathType nodeTypeBelow = this.nodeEvaluator.getPathType(
                                new PathfindingContext(this.level, this.mob),
                                unswizzle(obx, by + (invertY ? 1 : -1), obz, ax, ay, az, Direction.Axis.X), unswizzle(obx, by + (invertY ? 1 : -1), obz, ax, ay, az, Direction.Axis.Y), unswizzle(obx, by + (invertY ? 1 : -1), obz, ax, ay, az, Direction.Axis.Z));

                        if (nodeTypeBelow == PathType.WATER) {
                            return false;
                        }

                        if (nodeTypeBelow == PathType.LAVA) {
                            return false;
                        }

                        if (nodeTypeBelow == PathType.OPEN) {
                            return false;
                        }

                        PathType nodeType = this.nodeEvaluator.getPathType(
                                new PathfindingContext(this.level, this.mob),
                                unswizzle(obx, by, obz, ax, ay, az, Direction.Axis.X), unswizzle(obx, by, obz, ax, ay, az, Direction.Axis.Y), unswizzle(obx, by, obz, ax, ay, az, Direction.Axis.Z)
                        );
                        float f = this.mob.getPathfindingMalus(nodeType);

                        if (f < 0.0F || f >= 8.0F) {
                            return false;
                        }

                        if (nodeType == PathType.DAMAGE_FIRE || nodeType == PathType.DANGER_FIRE || nodeType == PathType.DAMAGE_OTHER) {
                            return false;
                        }
                    }
                }
            }

            return true;
        }
    }

    protected boolean isPositionClear(int x, int y, int z, int sizeX, int sizeY, int sizeZ, Vec3 start, double dx, double dz, double minDotProduct, Direction.Axis ax, Direction.Axis ay, Direction.Axis az) {
        for (BlockPos pos : BlockPos.betweenClosed(new BlockPos(x, y, z), new BlockPos(x + sizeX - 1, y + sizeY - 1, z + sizeZ - 1))) {
            if (!this.level.isLoaded(pos)) continue;
            double offsetX = swizzle(pos.getX(), pos.getY(), pos.getZ(), ax) + 0.5D - swizzle(start, ax);
            double pffsetZ = swizzle(pos.getX(), pos.getY(), pos.getZ(), az) + 0.5D - swizzle(start, az);

            if (offsetX * dx + pffsetZ * dz >= minDotProduct) {
                BlockState state = this.level.getBlockState(pos);

                if (!state.isPathfindable(PathComputationType.LAND)) {
                    return false;
                }
            }
        }

        return true;
    }
}
