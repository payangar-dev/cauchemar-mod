package com.payangar.cauchemar.entity;

import com.payangar.cauchemar.entity.ai.SeekDarknessGoal;
import com.payangar.cauchemar.entity.climber.BetterSpiderPathNavigator;
import com.payangar.cauchemar.entity.climber.ClimberJumpController;
import com.payangar.cauchemar.entity.climber.ClimberLookController;
import com.payangar.cauchemar.entity.climber.ClimberMatrix4f;
import com.payangar.cauchemar.entity.climber.ClimberMoveController;
import com.payangar.cauchemar.entity.climber.CollisionSmoothingUtil;
import com.payangar.cauchemar.entity.climber.IClimberEntity;
import com.payangar.cauchemar.entity.climber.Orientation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.ai.goal.LookAtPlayerGoal;
import net.minecraft.world.entity.ai.goal.RandomLookAroundGoal;
import net.minecraft.world.entity.ai.goal.WaterAvoidingRandomStrollGoal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.entity.PartEntity;
import org.apache.commons.lang3.tuple.Pair;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The Mother Spider, first monster of the mod.
 *
 * <p>Wanders (idle/walk), tracks players with its head, and freezes into an "observe" stance when
 * hit (placeholder trigger). It also climbs walls/ceilings: a surface-relative movement + a smoothed
 * surface normal orient the body to whatever it stands on. The climbing code is ported from Nyf's
 * Spiders (self-contained 1.20.4 branch) and adapted to 1.21.1.
 */
public class MotherSpiderEntity extends Monster implements GeoEntity, IClimberEntity {

    private static final RawAnimation IDLE = RawAnimation.begin().thenLoop("animation.idle");
    private static final RawAnimation WALK = RawAnimation.begin().thenLoop("animation.walk");
    private static final RawAnimation OBSERVE = RawAnimation.begin().thenLoop("animation.observe");

    private static final EntityDataAccessor<Boolean> OBSERVING =
            SynchedEntityData.defineId(MotherSpiderEntity.class, EntityDataSerializers.BOOLEAN);
    private static final EntityDataAccessor<Integer> MOVEMENT_MODE =
            SynchedEntityData.defineId(MotherSpiderEntity.class, EntityDataSerializers.INT);
    private static final int OBSERVE_DURATION_TICKS = 20 * 20;

    /** Below this 3D speed (blocks per tick) the spider is treated as standing still. */
    private static final double MOVING_THRESHOLD = 0.01;
    /** Keep "moving" true this many ticks after movement stops, so micro-stops during erratic pathing
     *  don't restart the walk animation (which pops the legs) nor flicker the foot-IK knee read. */
    private static final int WALK_ANIM_HOLD_TICKS = 6;
    /** Effective light level at/below which a spot counts as shadow (0 = only pitch black). */
    private static final int SHADOW_LIGHT_THRESHOLD = 0;
    /** Horizontal radius (blocks) scanned for a dark spot before the spider panics. */
    private static final int SHADOW_SEARCH_RADIUS = 8;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private int observeTicksLeft = 0;
    private int walkAnimHoldTicks = 0;

    /** Per-body-part hit boxes (head, cephalothorax, abdomen) so the large model is hittable. */
    private final MotherSpiderPartEntity[] subEntities;

    /** Client-only: smoothed per-leg IK foot height (leg-plane vertical); NaN = not yet initialised. */
    private final float[] legFootIkV = new float[8];

    // --- Climbing (ported from Nyf's Spiders) ---
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

    private double preMoveY;
    private Vec3 jumpDir;

    public MotherSpiderEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.orientation = this.calculateOrientation(1);
        this.moveControl = new ClimberMoveController<>(this);
        this.lookControl = new ClimberLookController<>(this);
        this.jumpControl = new ClimberJumpController<>(this);

        // Body-local hit boxes (right, up, forward in blocks; forward = head-first). The small
        // collision box keeps climbing/pathfinding clean while these cover the visible body.
        this.subEntities = new MotherSpiderPartEntity[]{
                new MotherSpiderPartEntity(this, 0.0, 0.72, 0.97, 0.9f, 0.8f),   // head (front)
                new MotherSpiderPartEntity(this, 0.0, 0.72, 0.19, 0.8f, 0.7f),   // cephalothorax (center)
                new MotherSpiderPartEntity(this, 0.0, 0.97, -1.19, 1.8f, 1.3f),  // abdomen (rear, the big target)
        };
        // Reserve consecutive entity ids for the parts (NeoForge multipart requirement).
        this.setId(ENTITY_COUNTER.getAndAdd(this.subEntities.length + 1) + 1);
        // The collision box is tiny relative to the model, so never frustum-cull the spider.
        this.noCulling = true;

        // Foot IK heights start uninitialised so the first frame snaps to the target (no lerp pop).
        Arrays.fill(this.legFootIkV, Float.NaN);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(OBSERVING, false);
        builder.define(MOVEMENT_MODE, MovementMode.WANDER.ordinal());
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
        // Light avoidance takes priority over idle wandering: flee to shadow, panic if none near.
        this.goalSelector.addGoal(1, new SeekDarknessGoal(this, MovementMode.SPRINT.navSpeedModifier, SHADOW_LIGHT_THRESHOLD, SHADOW_SEARCH_RADIUS));
        this.goalSelector.addGoal(2, new WaterAvoidingRandomStrollGoal(this, MovementMode.WANDER.navSpeedModifier) {
            @Override
            public boolean canUse() {
                return !isObserving() && super.canUse();
            }

            @Override
            public boolean canContinueToUse() {
                return !isObserving() && super.canContinueToUse();
            }

            @Override
            public void start() {
                setMovementMode(MovementMode.WANDER);
                super.start();
            }
        });
        this.goalSelector.addGoal(8, new LookAtPlayerGoal(this, Player.class, 8.0F));
        this.goalSelector.addGoal(9, new RandomLookAroundGoal(this));
    }

    @Override
    public void tick() {
        super.tick();

        // Keep the per-part hit boxes glued to the body in its current surface orientation. Runs on
        // both sides (the parts are debug-visible client-side with F3+B and hit server-side).
        for (MotherSpiderPartEntity part : this.subEntities) {
            part.positionSelf();
        }

        // Track movement with a short hold so micro-stops during erratic pathing don't restart the
        // walk animation (which pops the legs) nor flicker the foot-IK knee read.
        double dx = this.getX() - this.xo;
        double dy = this.getY() - this.yo;
        double dz = this.getZ() - this.zo;
        if (dx * dx + dy * dy + dz * dz > MOVING_THRESHOLD * MOVING_THRESHOLD) {
            this.walkAnimHoldTicks = WALK_ANIM_HOLD_TICKS;
        } else if (this.walkAnimHoldTicks > 0) {
            this.walkAnimHoldTicks--;
        }

        if (!this.level().isClientSide && this.observeTicksLeft > 0) {
            this.observeTicksLeft--;
            this.getNavigation().stop();
            if (this.observeTicksLeft == 0) {
                this.entityData.set(OBSERVING, false);
            }
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean result = super.hurt(source, amount);
        if (result && !this.level().isClientSide) {
            this.observeTicksLeft = OBSERVE_DURATION_TICKS;
            this.entityData.set(OBSERVING, true);
            this.getNavigation().stop();
        }
        return result;
    }

    // ===================== Multipart hit boxes =====================

    /** A hit landed on one of the body-part boxes is dealt to the spider itself. */
    public boolean hurt(MotherSpiderPartEntity part, DamageSource source, float amount) {
        return this.hurt(source, amount);
    }

    @Override
    public boolean isMultipartEntity() {
        return true;
    }

    @Override
    public PartEntity<?>[] getParts() {
        return this.subEntities;
    }

    @Override
    public void setId(int id) {
        super.setId(id);
        // Re-assign consecutive ids to the parts so client and server agree (NeoForge multipart fix).
        if (this.subEntities != null) {
            for (int i = 0; i < this.subEntities.length; i++) {
                this.subEntities[i].setId(id + i + 1);
            }
        }
    }

    @Override
    protected boolean isImmobile() {
        return super.isImmobile() || isObserving();
    }

    public boolean isObserving() {
        return this.entityData.get(OBSERVING);
    }

    /** Whether the walk animation / foot IK should treat the spider as moving (with a short hold). */
    public boolean isMovingForAnimation() {
        return this.walkAnimHoldTicks > 0;
    }

    public MovementMode getMovementMode() {
        return MovementMode.values()[this.entityData.get(MOVEMENT_MODE)];
    }

    /** Sets the locomotion mode (server side). It is synced so the client drives the walk speed. */
    public void setMovementMode(MovementMode mode) {
        this.entityData.set(MOVEMENT_MODE, mode.ordinal());
    }

    /**
     * Eases the IK foot height of one leg toward {@code target} so feet glide onto a new surface
     * instead of snapping when the raycast crosses a block edge. Client-side visual smoothing only;
     * the first call per leg snaps (NaN sentinel) to avoid a pop on spawn.
     */
    public float smoothLegFootV(int leg, float target, float factor) {
        float current = this.legFootIkV[leg];
        float next = Float.isNaN(current) ? target : current + (target - current) * factor;
        this.legFootIkV[leg] = next;
        return next;
    }

    @Override
    public int getMaxHeadYRot() {
        return 180;
    }

    @Override
    protected void checkFallDamage(double y, boolean onGround, net.minecraft.world.level.block.state.BlockState state, BlockPos pos) {
        // Climbers do not take fall damage.
    }

    @Override
    public boolean onClimbable() {
        // Climbing is handled by our own surface-relative travel, not the vanilla ladder system.
        return false;
    }

    // ===================== Climbing: surface-aware navigation =====================

    @Override
    protected PathNavigation createNavigation(Level level) {
        // Surface-aware navigation: the A* search can place path nodes on walls and ceilings, so the
        // spider deliberately routes onto any climbable surface (not only when it bumps one).
        BetterSpiderPathNavigator<MotherSpiderEntity> navigation = new BetterSpiderPathNavigator<>(this, level, false);
        navigation.setCanFloat(true);
        return navigation;
    }

    @Override
    public void onPathingObstructed(Direction facing) {
        // No-op: when the path meets a surface the spider sticks to it through the on-contact climbing
        // in travel()/move(). The hook stays for the IAdvancedPathFindingEntity contract.
    }

    @Override
    public int getMaxFallDistance() {
        // A climber routes along surfaces rather than path-falling.
        return 0;
    }

    // ===================== Climbing: surface travel & sticking =====================

    @Override
    public void travel(Vec3 relative) {
        boolean canTravel = this.isEffectiveAi() || this.isControlledByLocalInstance();
        this.isTravelingInFluid = false;

        FluidState fluidState = this.level().getFluidState(this.blockPosition());

        if (!this.canClimbInWater && this.isInWater() && this.isAffectedByFluids() && !this.canStandOnFluid(fluidState)) {
            this.isTravelingInFluid = true;
            if (canTravel) {
                super.travel(relative);
                this.updateOffsetsAndOrientation();
                return;
            }
        } else if (!this.canClimbInLava && this.isInLava() && this.isAffectedByFluids() && !this.canStandOnFluid(fluidState)) {
            this.isTravelingInFluid = true;
            if (canTravel) {
                super.travel(relative);
                this.updateOffsetsAndOrientation();
                return;
            }
        } else if (canTravel) {
            this.updateWalkingSide();
            this.travelOnGround(relative);
        }

        if (!canTravel) {
            this.calculateEntityAnimation(true);
        }

        this.updateOffsetsAndOrientation();
    }

    @Override
    public void move(MoverType type, Vec3 movement) {
        this.preMoveY = this.getY();
        super.move(type, movement);
        if (Math.abs(this.getY() - this.preMoveY - movement.y) > 0.000001D) {
            this.setDeltaMovement(this.getDeltaMovement().multiply(1, 0, 1));
        }
        this.setOnGround(this.horizontalCollision || this.verticalCollision);
    }

    private double getClimbGravity() {
        if (this.isNoGravity()) {
            return 0;
        }
        double gravity = 0.08D;
        boolean isFalling = this.getDeltaMovement().y <= 0.0D;
        if (isFalling && this.hasEffect(MobEffects.SLOW_FALLING)) {
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
        return this.onGround() ? this.getSpeed() * (0.16277136F / (slipperiness * slipperiness * slipperiness)) : 0.02F;
    }

    private float getBlockSlipperiness(BlockPos pos) {
        return this.level().getBlockState(pos).getBlock().getFriction() * 0.91f;
    }

    @Override
    public Pair<Direction, Vec3> getGroundDirection() {
        return this.groundDirection;
    }

    @Override
    public float getMovementSpeed() {
        return (float) this.getAttributeValue(Attributes.MOVEMENT_SPEED);
    }

    @Override
    public void setJumpDirection(Vec3 dir) {
        this.jumpDir = dir != null ? dir.normalize() : null;
    }

    private void setStepHeightBase(double value) {
        AttributeInstance attr = this.getAttribute(Attributes.STEP_HEIGHT);
        if (attr != null) {
            attr.setBaseValue(value);
        }
    }

    private void setPositionToBoundingBox() {
        AABB box = this.getBoundingBox();
        this.setPosRaw((box.minX + box.maxX) / 2.0D, box.minY, (box.minZ + box.maxZ) / 2.0D);
    }

    private void travelOnGround(Vec3 relative) {
        Orientation orientation = this.getOrientation();

        Vec3 forwardVector = orientation.getGlobal(this.getYRot(), 0);
        Vec3 strafeVector = orientation.getGlobal(this.getYRot() + 90.0f, 0);
        Vec3 upVector = orientation.getGlobal(this.getYRot(), -90.0f);

        Pair<Direction, Vec3> groundDirection = this.getGroundDirection();
        Vec3 stickingForce = this.getStickingForce(groundDirection);

        boolean isFalling = this.getDeltaMovement().y <= 0.0D;
        if (isFalling && this.hasEffect(MobEffects.SLOW_FALLING)) {
            this.fallDistance = 0;
        }

        float forward = (float) relative.z;
        float strafe = (float) relative.x;

        if (forward != 0 || strafe != 0) {
            float slipperiness = 0.91f;

            if (this.onGround()) {
                BlockPos offsetPos = this.blockPosition().relative(groundDirection.getLeft());
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

                double px = this.getX();
                double py = this.getY();
                double pz = this.getZ();
                Vec3 motion = this.getDeltaMovement();
                AABB aabb = this.getBoundingBox();

                // Probe actual movement vector.
                this.move(MoverType.SELF, movementOffset);
                Vec3 movementDir = new Vec3(this.getX() - px, this.getY() - py, this.getZ() - pz).normalize();
                this.setBoundingBox(aabb);
                this.setPositionToBoundingBox();
                this.setDeltaMovement(motion);

                // Probe collision normal.
                Vec3 probeVector = new Vec3(
                        Math.abs(movementDir.x) < 0.001D ? -Math.signum(upVector.x) : 0,
                        Math.abs(movementDir.y) < 0.001D ? -Math.signum(upVector.y) : 0,
                        Math.abs(movementDir.z) < 0.001D ? -Math.signum(upVector.z) : 0).normalize().scale(0.0001D);
                this.move(MoverType.SELF, probeVector);

                Vec3 collisionNormal = new Vec3(
                        Math.abs(this.getX() - px - probeVector.x) > 0.000001D ? Math.signum(-probeVector.x) : 0,
                        Math.abs(this.getY() - py - probeVector.y) > 0.000001D ? Math.signum(-probeVector.y) : 0,
                        Math.abs(this.getZ() - pz - probeVector.z) > 0.000001D ? Math.signum(-probeVector.z) : 0).normalize();

                this.setBoundingBox(aabb);
                this.setPositionToBoundingBox();
                this.setDeltaMovement(motion);

                // Movement vector projected onto the surface.
                Vec3 surfaceMovementDir = movementDir.subtract(collisionNormal.scale(collisionNormal.dot(movementDir))).normalize();

                boolean isInnerCorner = Math.abs(collisionNormal.x) + Math.abs(collisionNormal.y) + Math.abs(collisionNormal.z) > 1.0001f;

                if (!isInnerCorner) {
                    movementDir = surfaceMovementDir;
                }

                stickingForce = stickingForce.subtract(surfaceMovementDir.scale(surfaceMovementDir.normalize().dot(stickingForce)));

                float moveSpeed = Mth.sqrt(forward * forward + strafe * strafe);
                this.setDeltaMovement(this.getDeltaMovement().add(movementDir.scale(moveSpeed)));
            }
        }

        this.setDeltaMovement(this.getDeltaMovement().add(stickingForce));

        double px = this.getX();
        double py = this.getY();
        double pz = this.getZ();
        Vec3 motion = this.getDeltaMovement();

        this.move(MoverType.SELF, motion);

        this.prevAttachedSides = this.attachedSides;
        this.attachedSides = new Vec3(
                Math.abs(this.getX() - px - motion.x) > 0.001D ? -Math.signum(motion.x) : 0,
                Math.abs(this.getY() - py - motion.y) > 0.001D ? -Math.signum(motion.y) : 0,
                Math.abs(this.getZ() - pz - motion.z) > 0.001D ? -Math.signum(motion.z) : 0);

        float slipperiness = 0.91f;
        if (this.onGround()) {
            this.fallDistance = 0;
            BlockPos offsetPos = this.blockPosition().relative(groundDirection.getLeft());
            slipperiness = this.getBlockSlipperiness(offsetPos);
        }

        motion = this.getDeltaMovement();
        Vec3 orthogonalMotion = upVector.scale(upVector.dot(motion));
        Vec3 tangentialMotion = motion.subtract(orthogonalMotion);

        this.setDeltaMovement(
                tangentialMotion.x * slipperiness + orthogonalMotion.x * 0.98f,
                tangentialMotion.y * slipperiness + orthogonalMotion.y * 0.98f,
                tangentialMotion.z * slipperiness + orthogonalMotion.z * 0.98f);

        boolean detachedX = this.attachedSides.x != this.prevAttachedSides.x && Math.abs(this.attachedSides.x) < 0.001D;
        boolean detachedY = this.attachedSides.y != this.prevAttachedSides.y && Math.abs(this.attachedSides.y) < 0.001D;
        boolean detachedZ = this.attachedSides.z != this.prevAttachedSides.z && Math.abs(this.attachedSides.z) < 0.001D;

        if (detachedX || detachedY || detachedZ) {
            float stepHeight = this.maxUpStep();
            this.setStepHeightBase(0);

            boolean prevOnGround = this.onGround();
            boolean prevCollidedHorizontally = this.horizontalCollision;
            boolean prevCollidedVertically = this.verticalCollision;

            this.move(MoverType.SELF, new Vec3(
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

            AABB aabb = this.getBoundingBox();
            motion = this.getDeltaMovement();

            for (int i = 0; i < 2 && !this.onGround(); i++) {
                this.move(MoverType.SELF, attachVector.scale(attachDst));
            }

            this.setStepHeightBase(stepHeight);

            if (!this.onGround()) {
                this.setBoundingBox(aabb);
                this.setPositionToBoundingBox();
                this.setDeltaMovement(motion);
                this.setOnGround(prevOnGround);
                this.horizontalCollision = prevCollidedHorizontally;
                this.verticalCollision = prevCollidedVertically;
            } else {
                this.setDeltaMovement(Vec3.ZERO);
            }
        }

        this.calculateEntityAnimation(true);
    }

    @Override
    public void jumpFromGround() {
        if (!this.onJump()) {
            super.jumpFromGround();
        }
    }

    private boolean onJump() {
        if (this.jumpDir != null) {
            float jumpStrength = this.getJumpPower();
            if (this.hasEffect(MobEffects.JUMP)) {
                jumpStrength += 0.1F * (float) (this.getEffect(MobEffects.JUMP).getAmplifier() + 1);
            }

            Vec3 motion = this.getDeltaMovement();
            Vec3 orthogonalMotion = this.jumpDir.scale(this.jumpDir.dot(motion));
            Vec3 tangentialMotion = motion.subtract(orthogonalMotion);

            this.setDeltaMovement(
                    tangentialMotion.x + this.jumpDir.x * jumpStrength,
                    tangentialMotion.y + this.jumpDir.y * jumpStrength,
                    tangentialMotion.z + this.jumpDir.z * jumpStrength);

            if (this.isSprinting()) {
                Vec3 boost = this.getOrientation().getGlobal(this.getYRot(), 0).scale(0.2f);
                this.setDeltaMovement(this.getDeltaMovement().add(boost));
            }

            this.hasImpulse = true;
            return true;
        }
        return false;
    }

    // ===================== Climbing: surface normal / attachment =====================

    /** Picks the most likely surface to stick to and a weighted "down" direction toward it. */
    private void updateWalkingSide() {
        AABB entityBox = this.getBoundingBox();

        double closestFacingDst = Double.MAX_VALUE;
        Direction closestFacing = null;
        Vec3 weighting = new Vec3(0, 0, 0);

        float stickingDistance = this.zza != 0 ? 1.5f : 0.1f;

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
        for (VoxelShape shape : this.level().getBlockCollisions(this, aabb)) {
            shape.forAllBoxes(action);
        }
    }

    @Override
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
        Vec3 direction = this.getOrientation().getGlobal(this.getYRot(), this.getXRot());

        boolean isAttached = false;

        double baseStickingOffsetX = 0.0f;
        double baseStickingOffsetY = this.getVerticalOffset(1);
        double baseStickingOffsetZ = 0.0f;
        Vec3 baseOrientationNormal = new Vec3(0, 1, 0);

        if (!this.isTravelingInFluid && this.onGround() && this.getVehicle() == null) {
            Vec3 p = this.position();
            Vec3 s = p.add(0, this.getBbHeight() * 0.5f, 0);
            AABB inclusionBox = new AABB(s.x, s.y, s.z, s.x, s.y, s.z).inflate(this.collisionsInclusionRange);

            Pair<Vec3, Vec3> attachmentPoint = CollisionSmoothingUtil.findClosestPoint(
                    consumer -> this.forEachCollisionBox(inclusionBox, consumer),
                    s, this.attachmentNormal.scale(-1), this.collisionsSmoothingRange, 1.0f, 0.001f, 20, 0.05f, s);

            AABB entityBox = this.getBoundingBox();

            if (attachmentPoint != null) {
                Vec3 attachmentPos = attachmentPoint.getLeft();

                double dx = Math.max(entityBox.minX - attachmentPos.x, attachmentPos.x - entityBox.maxX);
                double dy = Math.max(entityBox.minY - attachmentPos.y, attachmentPos.y - entityBox.maxY);
                double dz = Math.max(entityBox.minZ - attachmentPos.z, attachmentPos.z - entityBox.maxZ);

                if (Math.max(dx, Math.max(dy, dz)) < 0.5f) {
                    isAttached = true;

                    this.lastAttachmentOffsetX = Mth.clamp(attachmentPos.x - p.x, -this.getBbWidth() / 2, this.getBbWidth() / 2);
                    this.lastAttachmentOffsetY = Mth.clamp(attachmentPos.y - p.y, 0, this.getBbHeight());
                    this.lastAttachmentOffsetZ = Mth.clamp(attachmentPos.z - p.z, -this.getBbWidth() / 2, this.getBbWidth() / 2);
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
        if (!this.level().isClientSide) {
            Pair<Float, Float> newRotations = this.getOrientation().getLocalRotation(direction);

            float yawDelta = newRotations.getLeft() - this.getYRot();
            float pitchDelta = newRotations.getRight() - this.getXRot();

            this.prevOrientationYawDelta = this.orientationYawDelta;
            this.orientationYawDelta = yawDelta;

            this.setYRot(Mth.wrapDegrees(this.getYRot() + yawDelta));
            this.yRotO = this.wrapAngleInRange(this.yRotO, this.getYRot());
            this.lerpYRot = Mth.wrapDegrees(this.lerpYRot + yawDelta);

            this.yBodyRot = Mth.wrapDegrees(this.yBodyRot + yawDelta);
            this.yBodyRotO = this.wrapAngleInRange(this.yBodyRotO, this.yBodyRot);

            this.yHeadRot = Mth.wrapDegrees(this.yHeadRot + yawDelta);
            this.yHeadRotO = this.wrapAngleInRange(this.yHeadRotO, this.yHeadRot);
            this.lerpYHeadRot = Mth.wrapDegrees(this.lerpYHeadRot + yawDelta);

            this.setXRot(Mth.wrapDegrees(this.getXRot() + pitchDelta));
            this.xRotO = this.wrapAngleInRange(this.xRotO, this.getXRot());
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

    // ===================== IClimberEntity (rendering accessors) =====================

    @Override
    public Orientation getOrientation() {
        return this.orientation;
    }

    @Override
    public void setRenderOrientation(Orientation orientation) {
        this.renderOrientation = orientation;
    }

    @Override
    public Orientation getRenderOrientation() {
        return this.renderOrientation;
    }

    @Override
    public float getVerticalOffset(float partialTicks) {
        return 0.075f;
    }

    @Override
    public float getAttachmentOffset(Direction.Axis axis, float partialTicks) {
        return switch (axis) {
            case X -> (float) (this.prevAttachmentOffsetX + (this.attachmentOffsetX - this.prevAttachmentOffsetX) * partialTicks);
            case Y -> (float) (this.prevAttachmentOffsetY + (this.attachmentOffsetY - this.prevAttachmentOffsetY) * partialTicks);
            case Z -> (float) (this.prevAttachmentOffsetZ + (this.attachmentOffsetZ - this.prevAttachmentOffsetZ) * partialTicks);
        };
    }

    // ===================== Attributes & animation =====================

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 80.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.ATTACK_DAMAGE, 6.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.5)
                .add(Attributes.FOLLOW_RANGE, 24.0)
                // Low step height: the spider climbs surfaces instead of stepping up blocks.
                .add(Attributes.STEP_HEIGHT, 0.1);
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "movement", 5, state -> {
            if (isObserving()) {
                state.getController().setAnimationSpeed(1.0);
                return state.setAndContinue(OBSERVE);
            }

            // Moving is tracked in tick() with a short hold (3D, climb-aware), so the walk clip does
            // not restart on micro-stops. Walk playback speed is fixed per mode (wander x1/walk x2/sprint x4).
            if (isMovingForAnimation()) {
                state.getController().setAnimationSpeed(getMovementMode().animationSpeed);
                return state.setAndContinue(WALK);
            }

            state.getController().setAnimationSpeed(1.0);
            return state.setAndContinue(IDLE);
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return this.cache;
    }

    /**
     * Locomotion modes. Each pairs a navigation speed (a multiplier on {@code MOVEMENT_SPEED}, passed
     * to the movement goals) with a fixed walk-animation playback speed. WANDER strolls slowly with a
     * normal gait; WALK and SPRINT keep urgency by playing the legs faster. The active mode is synced
     * to the client so the animation speed matches what the server is doing.
     */
    public enum MovementMode {
        /** No objective, just roaming: slow body, normal-paced legs. */
        WANDER(0.6, 1.0),
        /** Heading to an unhurried objective. */
        WALK(1.0, 2.0),
        /** Rushing to an urgent objective (fleeing to shadow / panic). */
        SPRINT(2.5, 4.0);

        public final double navSpeedModifier;
        public final double animationSpeed;

        MovementMode(double navSpeedModifier, double animationSpeed) {
            this.navSpeedModifier = navSpeedModifier;
            this.animationSpeed = animationSpeed;
        }
    }
}
