package com.payangar.cauchemar.movement.climber;
import net.minecraft.world.entity.Mob;
import com.payangar.cauchemar.movement.MoveIntent;
import net.minecraft.world.entity.ai.control.JumpControl;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.apache.commons.lang3.tuple.Pair;

import java.util.ArrayList;
import java.util.List;

/**
 * The Climber locomotion profile: the surface-relative movement physics and attachment state, lifted
 * out of the owning mob so the entity is a thin delegator. This is the execution facet of
 * the climbing profile (ported from Nyf's Spiders): it sticks the body to floors, walls and ceilings,
 * tracks the smoothed surface normal/orientation, rounds corners, and rebases the entity's rotations
 * into the surface frame.
 *
 * <p>It drives the owning mob (a {@link Mob} that is also a {@link ClimberHost}) through that mob's
 * public members plus the {@link ClimberHost} bridges for its protected/super behaviour, so the
 * profile carries no dependency on any specific mob class.
 */
public class ClimberLocomotion<T extends Mob & ClimberHost> {

    /** Below this in-plane move distance the heading from atan2 is unreliable; the body keeps its yaw. */
    private static final double MIN_INPLANE_TURN_DIST = 0.05;
    /** Max yaw turn toward the move target per tick (degrees). */
    private static final float MAX_YAW_TURN_PER_TICK = 30.0f;

    private final T mob;

    private double prevAttachmentOffsetX, prevAttachmentOffsetY, prevAttachmentOffsetZ;
    private double attachmentOffsetX, attachmentOffsetY, attachmentOffsetZ;
    private double lastAttachmentOffsetX, lastAttachmentOffsetY, lastAttachmentOffsetZ;

    private Vec3 attachmentNormal = new Vec3(0, 1, 0);
    private Vec3 prevAttachmentNormal = new Vec3(0, 1, 0);
    private Vec3 lastAttachmentOrientationNormal = new Vec3(0, 1, 0);

    private int attachedTicks = 5;

    private Vec3 attachedSides = new Vec3(0, 0, 0);
    private Vec3 prevAttachedSides = new Vec3(0, 0, 0);

    private boolean canClimbInWater = false;
    private boolean canClimbInLava = false;
    private boolean isTravelingInFluid = false;

    private final float collisionsInclusionRange = 2.0f;
    private final float collisionsSmoothingRange = 1.25f;

    private Orientation orientation;
    private Orientation renderOrientation;
    private Pair<Direction, Vec3> groundDirection = Pair.of(Direction.DOWN, new Vec3(0, -1, 0));

    private float prevOrientationYawDelta;
    private float orientationYawDelta;

    private double lerpYRot, lerpXRot, lerpYHeadRot;

    private Vec3 jumpDir;

    public ClimberLocomotion(T mob) {
        this.mob = mob;
        this.orientation = this.calculateOrientation(1);
    }

    // ===================== Surface travel & sticking =====================

    public void travel(Vec3 relative) {
        boolean canTravel = this.mob.isEffectiveAi() || this.mob.isControlledByLocalInstance();
        this.isTravelingInFluid = false;

        FluidState fluidState = this.mob.level().getFluidState(this.mob.blockPosition());

        if (!this.canClimbInWater && this.mob.isInWater() && this.mob.climberAffectedByFluids() && !this.mob.canStandOnFluid(fluidState)) {
            this.isTravelingInFluid = true;
            if (canTravel) {
                this.mob.climberSuperTravel(relative);
                this.updateOffsetsAndOrientation();
                return;
            }
        } else if (!this.canClimbInLava && this.mob.isInLava() && this.mob.climberAffectedByFluids() && !this.mob.canStandOnFluid(fluidState)) {
            this.isTravelingInFluid = true;
            if (canTravel) {
                this.mob.climberSuperTravel(relative);
                this.updateOffsetsAndOrientation();
                return;
            }
        } else if (canTravel) {
            this.updateWalkingSide();
            this.travelOnGround(relative);
        }

        if (!canTravel) {
            this.mob.climberUpdateAnimation();
        }

        this.updateOffsetsAndOrientation();
    }

    /**
     * Applies a {@link MoveIntent} from the steering layer for this tick: re-aims the body (heading
     * derived from the in-plane move against the CURRENT surface frame, which mutates each tick),
     * drives forward via setSpeed, and triggers the directional corner jump. Called by the move
     * controller; the resulting inputs are consumed by {@link #travel} later in the same tick.
     */
    public void applyMovement(MoveIntent intent) {
        Vec3 inPlane = intent.inPlaneMove();
        double targetDist = inPlane.length();

        if (targetDist < 0.0001D) {
            this.mob.setZza(0);
            return;
        }

        Orientation orientation = this.getOrientation();
        Vec3 dir = inPlane.normalize();

        // Only re-aim when the in-plane component is large enough to give a reliable heading.
        if (targetDist >= MIN_INPLANE_TURN_DIST) {
            float rx = (float) orientation.localZ.dot(dir);
            float ry = (float) orientation.localX.dot(dir);
            this.mob.setYRot(rotlerp(this.mob.getYRot(), 270.0f - (float) Math.toDegrees(Mth.atan2(rx, ry)), MAX_YAW_TURN_PER_TICK));
        }

        Vec3 jumpDir = intent.jumpDir();
        if (jumpDir != null) {
            this.mob.setSpeed((float) (intent.speed() * 0.5));
            JumpControl jumpController = this.mob.getJumpControl();
            if (jumpController instanceof ClimberJumpController) {
                ((ClimberJumpController<?>) jumpController).setJumping(jumpDir);
            }
        } else {
            this.mob.setSpeed((float) intent.speed());
        }
    }

    /** Copy of {@link net.minecraft.world.entity.ai.control.MoveControl}'s rotlerp (it is protected). */
    private static float rotlerp(float from, float to, float maxDelta) {
        float delta = Mth.wrapDegrees(to - from);
        if (delta > maxDelta) {
            delta = maxDelta;
        }
        if (delta < -maxDelta) {
            delta = -maxDelta;
        }
        float result = from + delta;
        if (result < 0.0F) {
            result += 360.0F;
        } else if (result > 360.0F) {
            result -= 360.0F;
        }
        return result;
    }

    private double getClimbGravity() {
        if (this.mob.isNoGravity()) {
            return 0;
        }
        double gravity = 0.08D;
        boolean isFalling = this.mob.getDeltaMovement().y <= 0.0D;
        if (isFalling && this.mob.hasEffect(MobEffects.SLOW_FALLING)) {
            gravity = 0.1D;
        }
        return gravity;
    }

    private Vec3 getStickingForce(Pair<Direction, Vec3> walkingSide) {
        double uprightness = Math.max(this.attachmentNormal.y, 0);
        double gravity = this.getClimbGravity();
        double stickingForce = gravity * uprightness + 0.08D * (1 - uprightness);
        return walkingSide.getRight().scale(stickingForce);
    }

    private float getRelevantMoveFactor(float slipperiness) {
        // Air move factor (vanilla getFlyingSpeed ~0.02) inlined; the ground branch is the usual case.
        return this.mob.onGround() ? this.mob.getSpeed() * (0.16277136F / (slipperiness * slipperiness * slipperiness)) : 0.02F;
    }

    private float getBlockSlipperiness(BlockPos pos) {
        return this.mob.level().getBlockState(pos).getBlock().getFriction() * 0.91f;
    }

    public Pair<Direction, Vec3> getGroundDirection() {
        return this.groundDirection;
    }

    public Direction getGroundSide() {
        return this.groundDirection.getLeft();
    }

    public boolean isAttachedToSurface() {
        return this.attachedTicks > 0;
    }

    public void setJumpDirection(Vec3 dir) {
        this.jumpDir = dir != null ? dir.normalize() : null;
    }

    private void setStepHeightBase(double value) {
        net.minecraft.world.entity.ai.attributes.AttributeInstance attr = this.mob.getAttribute(net.minecraft.world.entity.ai.attributes.Attributes.STEP_HEIGHT);
        if (attr != null) {
            attr.setBaseValue(value);
        }
    }

    private void setPositionToBoundingBox() {
        AABB box = this.mob.getBoundingBox();
        this.mob.setPosRaw((box.minX + box.maxX) / 2.0D, box.minY, (box.minZ + box.maxZ) / 2.0D);
    }

    private void travelOnGround(Vec3 relative) {
        Orientation orientation = this.getOrientation();

        Vec3 forwardVector = orientation.getGlobal(this.mob.getYRot(), 0);
        Vec3 strafeVector = orientation.getGlobal(this.mob.getYRot() + 90.0f, 0);
        Vec3 upVector = orientation.getGlobal(this.mob.getYRot(), -90.0f);

        Pair<Direction, Vec3> groundDirection = this.getGroundDirection();
        Vec3 stickingForce = this.getStickingForce(groundDirection);

        boolean isFalling = this.mob.getDeltaMovement().y <= 0.0D;
        if (isFalling && this.mob.hasEffect(MobEffects.SLOW_FALLING)) {
            this.mob.fallDistance = 0;
        }

        float forward = (float) relative.z;
        float strafe = (float) relative.x;

        if (forward != 0 || strafe != 0) {
            float slipperiness = 0.91f;

            if (this.mob.onGround()) {
                BlockPos offsetPos = this.mob.blockPosition().relative(groundDirection.getLeft());
                slipperiness = this.getBlockSlipperiness(offsetPos);
            }

            float f = forward * forward + strafe * strafe;
            if (f >= 1.0E-4F) {
                f = Math.max(Mth.sqrt(f), 1.0f);
                f = this.getRelevantMoveFactor(slipperiness) / f;
                forward *= f;
                strafe *= f;

                Vec3 movementOffset = new Vec3(
                        forwardVector.x * forward + strafeVector.x * strafe,
                        forwardVector.y * forward + strafeVector.y * strafe,
                        forwardVector.z * forward + strafeVector.z * strafe);

                double px = this.mob.getX();
                double py = this.mob.getY();
                double pz = this.mob.getZ();
                Vec3 motion = this.mob.getDeltaMovement();
                AABB aabb = this.mob.getBoundingBox();

                // Probe actual movement vector.
                this.mob.move(MoverType.SELF, movementOffset);
                Vec3 movementDir = new Vec3(this.mob.getX() - px, this.mob.getY() - py, this.mob.getZ() - pz).normalize();
                this.mob.setBoundingBox(aabb);
                this.setPositionToBoundingBox();
                this.mob.setDeltaMovement(motion);

                // Probe collision normal.
                Vec3 probeVector = new Vec3(
                        Math.abs(movementDir.x) < 0.001D ? -Math.signum(upVector.x) : 0,
                        Math.abs(movementDir.y) < 0.001D ? -Math.signum(upVector.y) : 0,
                        Math.abs(movementDir.z) < 0.001D ? -Math.signum(upVector.z) : 0).normalize().scale(0.0001D);
                this.mob.move(MoverType.SELF, probeVector);

                Vec3 collisionNormal = new Vec3(
                        Math.abs(this.mob.getX() - px - probeVector.x) > 0.000001D ? Math.signum(-probeVector.x) : 0,
                        Math.abs(this.mob.getY() - py - probeVector.y) > 0.000001D ? Math.signum(-probeVector.y) : 0,
                        Math.abs(this.mob.getZ() - pz - probeVector.z) > 0.000001D ? Math.signum(-probeVector.z) : 0).normalize();

                this.mob.setBoundingBox(aabb);
                this.setPositionToBoundingBox();
                this.mob.setDeltaMovement(motion);

                // Movement vector projected onto the surface.
                Vec3 surfaceMovementDir = movementDir.subtract(collisionNormal.scale(collisionNormal.dot(movementDir))).normalize();

                boolean isInnerCorner = Math.abs(collisionNormal.x) + Math.abs(collisionNormal.y) + Math.abs(collisionNormal.z) > 1.0001f;

                if (!isInnerCorner) {
                    movementDir = surfaceMovementDir;
                }

                stickingForce = stickingForce.subtract(surfaceMovementDir.scale(surfaceMovementDir.normalize().dot(stickingForce)));

                float moveSpeed = Mth.sqrt(forward * forward + strafe * strafe);
                this.mob.setDeltaMovement(this.mob.getDeltaMovement().add(movementDir.scale(moveSpeed)));
            }
        }

        this.mob.setDeltaMovement(this.mob.getDeltaMovement().add(stickingForce));

        double px = this.mob.getX();
        double py = this.mob.getY();
        double pz = this.mob.getZ();
        Vec3 motion = this.mob.getDeltaMovement();

        this.mob.move(MoverType.SELF, motion);

        this.prevAttachedSides = this.attachedSides;
        this.attachedSides = new Vec3(
                Math.abs(this.mob.getX() - px - motion.x) > 0.001D ? -Math.signum(motion.x) : 0,
                Math.abs(this.mob.getY() - py - motion.y) > 0.001D ? -Math.signum(motion.y) : 0,
                Math.abs(this.mob.getZ() - pz - motion.z) > 0.001D ? -Math.signum(motion.z) : 0);

        float slipperiness = 0.91f;
        if (this.mob.onGround()) {
            this.mob.fallDistance = 0;
            BlockPos offsetPos = this.mob.blockPosition().relative(groundDirection.getLeft());
            slipperiness = this.getBlockSlipperiness(offsetPos);
        }

        motion = this.mob.getDeltaMovement();
        Vec3 orthogonalMotion = upVector.scale(upVector.dot(motion));
        Vec3 tangentialMotion = motion.subtract(orthogonalMotion);

        this.mob.setDeltaMovement(
                tangentialMotion.x * slipperiness + orthogonalMotion.x * 0.98f,
                tangentialMotion.y * slipperiness + orthogonalMotion.y * 0.98f,
                tangentialMotion.z * slipperiness + orthogonalMotion.z * 0.98f);

        boolean detachedX = this.attachedSides.x != this.prevAttachedSides.x && Math.abs(this.attachedSides.x) < 0.001D;
        boolean detachedY = this.attachedSides.y != this.prevAttachedSides.y && Math.abs(this.attachedSides.y) < 0.001D;
        boolean detachedZ = this.attachedSides.z != this.prevAttachedSides.z && Math.abs(this.attachedSides.z) < 0.001D;

        if (detachedX || detachedY || detachedZ) {
            float stepHeight = this.mob.maxUpStep();
            this.setStepHeightBase(0);

            boolean prevOnGround = this.mob.onGround();
            boolean prevCollidedHorizontally = this.mob.horizontalCollision;
            boolean prevCollidedVertically = this.mob.verticalCollision;

            this.mob.move(MoverType.SELF, new Vec3(
                    detachedX ? -this.prevAttachedSides.x * 0.25f : 0,
                    detachedY ? -this.prevAttachedSides.y * 0.25f : 0,
                    detachedZ ? -this.prevAttachedSides.z * 0.25f : 0));

            Vec3 axis = this.prevAttachedSides.normalize();
            Vec3 attachVector = upVector.scale(-1);
            attachVector = attachVector.subtract(axis.scale(axis.dot(attachVector)));

            if (Math.abs(attachVector.x) > Math.abs(attachVector.y) && Math.abs(attachVector.x) > Math.abs(attachVector.z)) {
                attachVector = new Vec3(Math.signum(attachVector.x), 0, 0);
            } else if (Math.abs(attachVector.y) > Math.abs(attachVector.z)) {
                attachVector = new Vec3(0, Math.signum(attachVector.y), 0);
            } else {
                attachVector = new Vec3(0, 0, Math.signum(attachVector.z));
            }

            double attachDst = motion.length() + 0.1f;

            AABB aabb = this.mob.getBoundingBox();
            motion = this.mob.getDeltaMovement();

            for (int i = 0; i < 2 && !this.mob.onGround(); i++) {
                this.mob.move(MoverType.SELF, attachVector.scale(attachDst));
            }

            this.setStepHeightBase(stepHeight);

            if (!this.mob.onGround()) {
                this.mob.setBoundingBox(aabb);
                this.setPositionToBoundingBox();
                this.mob.setDeltaMovement(motion);
                this.mob.setOnGround(prevOnGround);
                this.mob.horizontalCollision = prevCollidedHorizontally;
                this.mob.verticalCollision = prevCollidedVertically;
            } else {
                this.mob.setDeltaMovement(Vec3.ZERO);
            }
        }

        this.mob.climberUpdateAnimation();
    }

    public boolean onJump() {
        if (this.jumpDir != null) {
            float jumpStrength = this.mob.climberJumpPower();
            if (this.mob.hasEffect(MobEffects.JUMP)) {
                jumpStrength += 0.1F * (float) (this.mob.getEffect(MobEffects.JUMP).getAmplifier() + 1);
            }

            Vec3 motion = this.mob.getDeltaMovement();
            Vec3 orthogonalMotion = this.jumpDir.scale(this.jumpDir.dot(motion));
            Vec3 tangentialMotion = motion.subtract(orthogonalMotion);

            this.mob.setDeltaMovement(
                    tangentialMotion.x + this.jumpDir.x * jumpStrength,
                    tangentialMotion.y + this.jumpDir.y * jumpStrength,
                    tangentialMotion.z + this.jumpDir.z * jumpStrength);

            if (this.mob.isSprinting()) {
                Vec3 boost = this.getOrientation().getGlobal(this.mob.getYRot(), 0).scale(0.2f);
                this.mob.setDeltaMovement(this.mob.getDeltaMovement().add(boost));
            }

            this.mob.hasImpulse = true;
            return true;
        }
        return false;
    }

    // ===================== Surface normal / attachment =====================

    /** Picks the most likely surface to stick to and a weighted "down" direction toward it. */
    private void updateWalkingSide() {
        AABB entityBox = this.mob.getBoundingBox();

        double closestFacingDst = Double.MAX_VALUE;
        Direction closestFacing = null;
        Vec3 weighting = new Vec3(0, 0, 0);

        float stickingDistance = this.mob.zza != 0 ? 1.5f : 0.1f;

        for (Direction facing : Direction.values()) {
            List<AABB> collisionBoxes = this.getCollisionBoxes(
                    entityBox.inflate(0.2f).expandTowards(facing.getStepX() * stickingDistance, facing.getStepY() * stickingDistance, facing.getStepZ() * stickingDistance));

            double closestDst = Double.MAX_VALUE;

            for (AABB collisionBox : collisionBoxes) {
                switch (facing) {
                    case EAST, WEST ->
                            closestDst = Math.min(closestDst, Math.abs(calculateXOffset(entityBox, collisionBox, -facing.getStepX() * stickingDistance)));
                    case UP, DOWN ->
                            closestDst = Math.min(closestDst, Math.abs(calculateYOffset(entityBox, collisionBox, -facing.getStepY() * stickingDistance)));
                    case NORTH, SOUTH ->
                            closestDst = Math.min(closestDst, Math.abs(calculateZOffset(entityBox, collisionBox, -facing.getStepZ() * stickingDistance)));
                }
            }

            if (closestDst < closestFacingDst) {
                closestFacingDst = closestDst;
                closestFacing = facing;
            }

            if (closestDst < Double.MAX_VALUE) {
                weighting = weighting.add(new Vec3(facing.getStepX(), facing.getStepY(), facing.getStepZ()).scale(1 - Math.min(closestDst, stickingDistance) / stickingDistance));
            }
        }

        if (closestFacing == null) {
            this.groundDirection = Pair.of(Direction.DOWN, new Vec3(0, -1, 0));
        } else {
            this.groundDirection = Pair.of(closestFacing, weighting.normalize().add(0, -0.001f, 0).normalize());
        }
    }

    private List<AABB> getCollisionBoxes(AABB aabb) {
        List<AABB> boxes = new ArrayList<>();
        this.forEachCollisionBox(aabb, (minX, minY, minZ, maxX, maxY, maxZ) -> boxes.add(new AABB(minX, minY, minZ, maxX, maxY, maxZ)));
        return boxes;
    }

    private void forEachCollisionBox(AABB aabb, Shapes.DoubleLineConsumer action) {
        for (VoxelShape shape : this.mob.level().getBlockCollisions(this.mob, aabb)) {
            shape.forAllBoxes(action);
        }
    }

    public Orientation calculateOrientation(float partialTicks) {
        Vec3 normal = this.prevAttachmentNormal.add(this.attachmentNormal.subtract(this.prevAttachmentNormal).scale(partialTicks));

        Vec3 localZ = new Vec3(0, 0, 1);
        Vec3 localY = new Vec3(0, 1, 0);
        Vec3 localX = new Vec3(1, 0, 0);

        float componentZ = (float) localZ.dot(normal);
        float componentY;
        float componentX = (float) localX.dot(normal);

        float yaw = (float) Math.toDegrees(Mth.atan2(componentX, componentZ));

        localZ = new Vec3(Math.sin(Math.toRadians(yaw)), 0, Math.cos(Math.toRadians(yaw)));
        localY = new Vec3(0, 1, 0);
        localX = new Vec3(Math.sin(Math.toRadians(yaw - 90)), 0, Math.cos(Math.toRadians(yaw - 90)));

        componentZ = (float) localZ.dot(normal);
        componentY = (float) localY.dot(normal);
        componentX = (float) localX.dot(normal);

        float pitch = (float) Math.toDegrees(Mth.atan2(Mth.sqrt(componentX * componentX + componentZ * componentZ), componentY));

        ClimberMatrix4f m = new ClimberMatrix4f();
        m.multiply(new ClimberMatrix4f((float) Math.toRadians(yaw), 0, 1, 0));
        m.multiply(new ClimberMatrix4f((float) Math.toRadians(pitch), 1, 0, 0));
        m.multiply(new ClimberMatrix4f((float) Math.toRadians((float) Math.signum(0.5f - componentY - componentZ - componentX) * yaw), 0, 1, 0));

        localZ = m.multiply(new Vec3(0, 0, -1));
        localY = m.multiply(new Vec3(0, 1, 0));
        localX = m.multiply(new Vec3(1, 0, 0));

        return new Orientation(normal, localZ, localY, localX, componentZ, componentY, componentX, yaw, pitch);
    }

    /**
     * Recomputes the smoothed surface normal + attachment offset and blends it in. On the server it
     * also rebases the entity's yaw/pitch into the new surface frame so the AI's "forward" stays
     * consistent across floor/wall/ceiling transitions. Ported from Nyf's {@code
     * updateOffsetsAndOrientation}.
     */
    private void updateOffsetsAndOrientation() {
        Vec3 direction = this.getOrientation().getGlobal(this.mob.getYRot(), this.mob.getXRot());

        boolean isAttached = false;

        double baseStickingOffsetX = 0.0f;
        double baseStickingOffsetY = this.getVerticalOffset(1);
        double baseStickingOffsetZ = 0.0f;
        Vec3 baseOrientationNormal = new Vec3(0, 1, 0);

        if (!this.isTravelingInFluid && this.mob.onGround() && this.mob.getVehicle() == null) {
            Vec3 p = this.mob.position();
            Vec3 s = p.add(0, this.mob.getBbHeight() * 0.5f, 0);
            AABB inclusionBox = new AABB(s.x, s.y, s.z, s.x, s.y, s.z).inflate(this.collisionsInclusionRange);

            Pair<Vec3, Vec3> attachmentPoint = CollisionSmoothingUtil.findClosestPoint(
                    consumer -> this.forEachCollisionBox(inclusionBox, consumer),
                    s, this.attachmentNormal.scale(-1), this.collisionsSmoothingRange, 1.0f, 0.001f, 20, 0.05f, s);

            AABB entityBox = this.mob.getBoundingBox();

            if (attachmentPoint != null) {
                Vec3 attachmentPos = attachmentPoint.getLeft();

                double dx = Math.max(entityBox.minX - attachmentPos.x, attachmentPos.x - entityBox.maxX);
                double dy = Math.max(entityBox.minY - attachmentPos.y, attachmentPos.y - entityBox.maxY);
                double dz = Math.max(entityBox.minZ - attachmentPos.z, attachmentPos.z - entityBox.maxZ);

                if (Math.max(dx, Math.max(dy, dz)) < 0.5f) {
                    isAttached = true;

                    this.lastAttachmentOffsetX = Mth.clamp(attachmentPos.x - p.x, -this.mob.getBbWidth() / 2, this.mob.getBbWidth() / 2);
                    this.lastAttachmentOffsetY = Mth.clamp(attachmentPos.y - p.y, 0, this.mob.getBbHeight());
                    this.lastAttachmentOffsetZ = Mth.clamp(attachmentPos.z - p.z, -this.mob.getBbWidth() / 2, this.mob.getBbWidth() / 2);
                    this.lastAttachmentOrientationNormal = attachmentPoint.getRight();
                }
            }
        }

        this.prevAttachmentOffsetX = this.attachmentOffsetX;
        this.prevAttachmentOffsetY = this.attachmentOffsetY;
        this.prevAttachmentOffsetZ = this.attachmentOffsetZ;
        this.prevAttachmentNormal = this.attachmentNormal;

        float attachmentBlend = this.attachedTicks * 0.2f;

        this.attachmentOffsetX = baseStickingOffsetX + (this.lastAttachmentOffsetX - baseStickingOffsetX) * attachmentBlend;
        this.attachmentOffsetY = baseStickingOffsetY + (this.lastAttachmentOffsetY - baseStickingOffsetY) * attachmentBlend;
        this.attachmentOffsetZ = baseStickingOffsetZ + (this.lastAttachmentOffsetZ - baseStickingOffsetZ) * attachmentBlend;
        this.attachmentNormal = baseOrientationNormal.add(this.lastAttachmentOrientationNormal.subtract(baseOrientationNormal).scale(attachmentBlend)).normalize();

        if (!isAttached) {
            this.attachedTicks = Math.max(0, this.attachedTicks - 1);
        } else {
            this.attachedTicks = Math.min(5, this.attachedTicks + 1);
        }

        this.orientation = this.calculateOrientation(1);

        // Rebase the entity rotations into the new surface frame (server side only; the client
        // receives the rebased rotations through normal entity sync and lerps them).
        if (!this.mob.level().isClientSide) {
            Pair<Float, Float> newRotations = this.getOrientation().getLocalRotation(direction);

            float yawDelta = newRotations.getLeft() - this.mob.getYRot();
            float pitchDelta = newRotations.getRight() - this.mob.getXRot();

            this.prevOrientationYawDelta = this.orientationYawDelta;
            this.orientationYawDelta = yawDelta;

            this.mob.setYRot(Mth.wrapDegrees(this.mob.getYRot() + yawDelta));
            this.mob.yRotO = this.wrapAngleInRange(this.mob.yRotO, this.mob.getYRot());
            this.lerpYRot = Mth.wrapDegrees(this.lerpYRot + yawDelta);

            this.mob.yBodyRot = Mth.wrapDegrees(this.mob.yBodyRot + yawDelta);
            this.mob.yBodyRotO = this.wrapAngleInRange(this.mob.yBodyRotO, this.mob.yBodyRot);

            this.mob.yHeadRot = Mth.wrapDegrees(this.mob.yHeadRot + yawDelta);
            this.mob.yHeadRotO = this.wrapAngleInRange(this.mob.yHeadRotO, this.mob.yHeadRot);
            this.lerpYHeadRot = Mth.wrapDegrees(this.lerpYHeadRot + yawDelta);

            this.mob.setXRot(Mth.wrapDegrees(this.mob.getXRot() + pitchDelta));
            this.mob.xRotO = this.wrapAngleInRange(this.mob.xRotO, this.mob.getXRot());
            this.lerpXRot = Mth.wrapDegrees(this.lerpXRot + pitchDelta);
        }
    }

    private float wrapAngleInRange(float angle, float target) {
        while (target - angle < -180.0F) {
            angle -= 360.0F;
        }
        while (target - angle >= 180.0F) {
            angle += 360.0F;
        }
        return angle;
    }

    private static double calculateXOffset(AABB aabb, AABB other, double offsetX) {
        if (other.maxY > aabb.minY && other.minY < aabb.maxY && other.maxZ > aabb.minZ && other.minZ < aabb.maxZ) {
            if (offsetX > 0.0D && other.maxX <= aabb.minX) {
                double dx = aabb.minX - other.maxX;
                if (dx < offsetX) {
                    offsetX = dx;
                }
            } else if (offsetX < 0.0D && other.minX >= aabb.maxX) {
                double dx = aabb.maxX - other.minX;
                if (dx > offsetX) {
                    offsetX = dx;
                }
            }
        }
        return offsetX;
    }

    private static double calculateYOffset(AABB aabb, AABB other, double offsetY) {
        if (other.maxX > aabb.minX && other.minX < aabb.maxX && other.maxZ > aabb.minZ && other.minZ < aabb.maxZ) {
            if (offsetY > 0.0D && other.maxY <= aabb.minY) {
                double dy = aabb.minY - other.maxY;
                if (dy < offsetY) {
                    offsetY = dy;
                }
            } else if (offsetY < 0.0D && other.minY >= aabb.maxY) {
                double dy = aabb.maxY - other.minY;
                if (dy > offsetY) {
                    offsetY = dy;
                }
            }
        }
        return offsetY;
    }

    private static double calculateZOffset(AABB aabb, AABB other, double offsetZ) {
        if (other.maxX > aabb.minX && other.minX < aabb.maxX && other.maxY > aabb.minY && other.minY < aabb.maxY) {
            if (offsetZ > 0.0D && other.maxZ <= aabb.minZ) {
                double dz = aabb.minZ - other.maxZ;
                if (dz < offsetZ) {
                    offsetZ = dz;
                }
            } else if (offsetZ < 0.0D && other.minZ >= aabb.maxZ) {
                double dz = aabb.maxZ - other.minZ;
                if (dz > offsetZ) {
                    offsetZ = dz;
                }
            }
        }
        return offsetZ;
    }

    // ===================== Rendering accessors =====================

    public Orientation getOrientation() {
        return this.orientation;
    }

    public void setRenderOrientation(Orientation orientation) {
        this.renderOrientation = orientation;
    }

    public Orientation getRenderOrientation() {
        return this.renderOrientation;
    }

    /** Small offset pushing the model out along the surface normal so it does not z-fight the floor. */
    public float getVerticalOffset(float partialTicks) {
        return 0.075f;
    }

    public float getAttachmentOffset(Direction.Axis axis, float partialTicks) {
        return switch (axis) {
            case X -> (float) (this.prevAttachmentOffsetX + (this.attachmentOffsetX - this.prevAttachmentOffsetX) * partialTicks);
            case Y -> (float) (this.prevAttachmentOffsetY + (this.attachmentOffsetY - this.prevAttachmentOffsetY) * partialTicks);
            case Z -> (float) (this.prevAttachmentOffsetZ + (this.attachmentOffsetZ - this.prevAttachmentOffsetZ) * partialTicks);
        };
    }
}
