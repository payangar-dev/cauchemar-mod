package com.payangar.cauchemar.entity;

import com.mojang.serialization.Dynamic;
import com.payangar.cauchemar.Cauchemar;
import com.payangar.cauchemar.emotion.EmotionType;
import com.payangar.cauchemar.emotion.Emotions;
import com.payangar.cauchemar.perception.Sight;
import com.payangar.cauchemar.entity.ai.SpiderAi;
import com.payangar.cauchemar.movement.climber.ClimberPathNavigator;
import com.payangar.cauchemar.movement.climber.ClimberJumpController;
import com.payangar.cauchemar.movement.climber.ClimberLookController;
import com.payangar.cauchemar.movement.climber.ClimberMoveController;
import com.payangar.cauchemar.movement.climber.ClimberHost;
import com.payangar.cauchemar.movement.climber.ClimberLocomotion;
import com.payangar.cauchemar.movement.climber.IClimberEntity;
import com.payangar.cauchemar.movement.climber.Orientation;
import com.payangar.cauchemar.movement.MoveIntent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.Vec3i;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.tags.GameEventTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.pathfinder.PathType;
import java.util.function.Predicate;
import net.minecraft.core.Direction;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gameevent.DynamicGameEventListener;
import net.minecraft.world.level.gameevent.EntityPositionSource;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.gameevent.GameEventListener;
import net.minecraft.world.level.gameevent.PositionSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.entity.PartEntity;
import org.apache.commons.lang3.tuple.Pair;
import software.bernie.geckolib.animatable.GeoEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.Arrays;
import java.util.function.BiConsumer;

/**
 * The Mother Spider, first monster of the mod.
 *
 * <p>Wanders (idle/walk), tracks players with its head, and freezes into an "observe" stance when
 * hit (placeholder trigger). It also climbs walls/ceilings: the surface-relative movement physics and
 * attachment state live in {@link ClimberLocomotion} (the Climber locomotion profile), which this
 * entity owns and delegates to. The climbing code is ported from Nyf's Spiders and adapted to 1.21.1.
 */
public class MotherSpiderEntity extends Monster implements GeoEntity, IClimberEntity, ClimberHost {

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
    /** Added pathfinding cost per light level, to bias routes toward shadow without forbidding light. */
    private static final float LIGHT_PATHING_MALUS_PER_LEVEL = 0.4f;

    /** Only light at or above this level frightens her; below it she tolerates the gloom and goes about
     *  her business (wandering, investigating). This is what lets her live in the dark without panicking
     *  at the faintest glow. Tuned in P6. */
    private static final int FEAR_LIGHT_THRESHOLD = 8;
    /** Fear added per light level above the threshold, per tick: drives the flee-to-shadow. */
    private static final float FEAR_PER_LIGHT_LEVEL = 3.0f;
    /** Fear added per tick while on fire (far stronger than light). */
    private static final float FEAR_ON_FIRE_PER_TICK = 20.0f;
    /** At or above this fear the spider flees to shadow (and sprints). Tuned in P6. */
    public static final float FLEE_FEAR_THRESHOLD = 30.0f;

    /** How far the spider hears vibrations (blocks), like the Warden's listener radius. */
    private static final int HEARING_RANGE = 16;
    /** Ticks a heard disturbance is remembered as a place to investigate. */
    private static final int DISTURBANCE_TTL = 200;
    /** Noise intensity (0..1) at or above which a sound frightens rather than intrigues (an explosion). */
    private static final float LOUD_NOISE_INTENSITY = 0.75f;
    /** Fear added from a loud noise, scaled by its intensity. */
    private static final float FEAR_PER_NOISE = 80.0f;
    /** Curiosity added from a moderate noise, scaled by its intensity. */
    private static final float CURIOSITY_PER_NOISE = 60.0f;
    /** A noise is worth investigating when its intensity times her attention reaches this. Below it she
     *  hears the sound (a little curiosity) but judges it not worth leaving cover for. */
    private static final float INVESTIGATE_SALIENCE_THRESHOLD = 0.35f;
    /** How much full hunger sharpens her attention to noises (a starving spider chases fainter sounds). */
    private static final float HUNGER_ATTENTION_GAIN = 1.0f;
    /** How much being on edge (curiosity) sharpens her attention to the next noise. */
    private static final float CURIOSITY_ATTENTION_GAIN = 0.5f;
    /** A noise within this range that she was not already watching makes her jump (a startle). */
    private static final float STARTLE_RADIUS = 4.0f;
    /** Fear from a startle right on top of her; scales linearly to 0 at {@link #STARTLE_RADIUS}. */
    private static final float STARTLE_FEAR_MAX = 60.0f;
    /** While within {@link #STARTLE_RADIUS} of a source she is wary of, fear is held at least this high,
     *  so she keeps retreating until she reaches a safe distance and only then calms. Above
     *  {@link #FLEE_FEAR_THRESHOLD} by design. */
    private static final float DANGER_FEAR_FLOOR = 40.0f;

    private final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);

    private int observeTicksLeft = 0;
    private int walkAnimHoldTicks = 0;

    /** Per-body-part hit boxes (head, cephalothorax, abdomen) so the large model is hittable. */
    private final MotherSpiderPartEntity[] subEntities;

    /** Client-only: smoothed per-leg IK foot height (leg-plane vertical); NaN = not yet initialised. */
    private final float[] legFootIkV = new float[8];

    // --- Climbing locomotion (the surface-relative physics + attachment state live in the profile) ---
    private final ClimberLocomotion<MotherSpiderEntity> locomotion;

    /** Pre-move Y, used by the move() override to cancel vertical motion on collision. */
    private double preMoveY;

    /** The spider's emotional state (drives that gate its behaviour); persisted in NBT. */
    private final Emotions emotions = new Emotions();

    // --- Hearing: a plain game-event listener (vibrations without the traveling sculk particle) ---
    private final DynamicGameEventListener<SpiderEars> dynamicGameEventListener;

    public MotherSpiderEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
        this.locomotion = new ClimberLocomotion<>(this);
        this.moveControl = new ClimberMoveController<>(this);
        this.lookControl = new ClimberLookController<>(this);
        this.jumpControl = new ClimberJumpController<>(this);

        // Hearing: the listener registers itself with the level via updateDynamicGameEventListener.
        this.dynamicGameEventListener = new DynamicGameEventListener<>(new SpiderEars());

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

    // ===================== Brain (AI) =====================
    // The spider runs on the vanilla Brain system (sensors -> memories -> behaviors -> activities),
    // wired in SpiderAi. No goalSelector goals: leaving registerGoals unoverridden keeps it empty.

    @Override
    protected Brain<?> makeBrain(Dynamic<?> dynamic) {
        return SpiderAi.makeBrain(this, dynamic);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Brain<MotherSpiderEntity> getBrain() {
        return (Brain<MotherSpiderEntity>) super.getBrain();
    }

    @Override
    protected void customServerAiStep() {
        if (this.level() instanceof ServerLevel serverLevel) {
            // Emotions first (decay toward rest), then the appraisal feeds today's stimuli, then the
            // brain reads the resulting drives and sets the walk target the behaviors want.
            this.emotions.tick();
            this.updateEmotions();
            this.getBrain().tick(serverLevel, this);
            // Animation gait follows the locomotion actually in progress (the active walk target's
            // speed), read once the brain has set it, so the legs can never race a crawling body nor
            // crawl while sprinting. Read before updateActivity, which may switch activities below.
            this.updateMovementMode();
        }
        super.customServerAiStep();
        // Select the active activity (the FSM switch) after the brain ticked this frame.
        SpiderAi.updateActivity(this);
    }

    /** The appraisal: turns the spider's situation into emotional stimuli. Grows each phase. */
    private void updateEmotions() {
        // Bright light -> fear: the core "shun the light" drive. Dim light (below the threshold) is
        // tolerated, so she only flees genuine brightness; reaching the gloom lets fear decay.
        int light = this.level().getMaxLocalRawBrightness(this.blockPosition());
        if (light >= FEAR_LIGHT_THRESHOLD) {
            this.emotions.add(EmotionType.FEAR, (light - FEAR_LIGHT_THRESHOLD + 1) * FEAR_PER_LIGHT_LEVEL);
        }
        if (this.isOnFire()) {
            this.emotions.add(EmotionType.FEAR, FEAR_ON_FIRE_PER_TICK);
        }
        // Too close to a source she is wary of: hold dread until she has backed off to a safe distance.
        // This is what makes the fearful retreat self-terminating (she calms once out of the danger
        // radius) and gives an unexplained thing right next to her the feel of a threat.
        // Only sustains fear that already exists (from a startle or a blast); it never creates dread for
        // a source she is calmly tracking. In practice she is only ever this close to a disturbance right
        // after being startled by it, so this holds the panic through the retreat and no longer.
        this.getBrain().getMemory(MemoryModuleType.DISTURBANCE_LOCATION).ifPresent(threat -> {
            if (this.emotions.get(EmotionType.FEAR) > 0.0f && threat.closerToCenterThan(this.position(), STARTLE_RADIUS)) {
                this.emotions.set(EmotionType.FEAR, Math.max(this.emotions.get(EmotionType.FEAR), DANGER_FEAR_FLOOR));
            }
        });
    }

    /**
     * Syncs the locomotion mode to the speed of the walk target currently being executed, so the leg
     * animation cadence always matches how fast the body is actually travelling (single source of
     * truth: the active walk target). When no target is set she is idle, so we keep the last mode (the
     * idle animation plays regardless of mode, gated by {@link #isMovingForAnimation()}).
     */
    private void updateMovementMode() {
        MovementMode mode = this.getBrain().getMemory(MemoryModuleType.WALK_TARGET)
                .map(target -> MovementMode.forNavSpeed(target.getSpeedModifier()))
                .orElseGet(this::getMovementMode);
        this.setMovementMode(mode);
    }

    /** The spider's emotional state, read by its Brain behaviors to gate actions. */
    public Emotions getEmotions() {
        return this.emotions;
    }

    /**
     * How attentive she is to noises right now (a multiplier on a noise's raw intensity). Hunger and
     * being on edge (curiosity) both sharpen it, so the same faint sound she ignores when calm and sated
     * becomes worth a look when she is starving or already alert.
     */
    private float attentionGain() {
        float hunger = this.emotions.get(EmotionType.HUNGER) / 100.0f;
        float curiosity = this.emotions.get(EmotionType.CURIOSITY) / 100.0f;
        return 1.0f + HUNGER_ATTENTION_GAIN * hunger + CURIOSITY_ATTENTION_GAIN * curiosity;
    }

    /** Whether a heard noise of this intensity is salient enough to leave cover and investigate. */
    private boolean isWorthInvestigating(float intensity) {
        return intensity * this.attentionGain() >= INVESTIGATE_SALIENCE_THRESHOLD;
    }

    /**
     * Whether she is free to pick up a new noise to investigate: only when idle. While already
     * investigating or fleeing, a fresh noise heightens curiosity but must not hijack her current walk
     * target, so she never gets pulled off a vantage approach by every footstep.
     */
    private boolean isFreeToInvestigate() {
        return this.getBrain().getActiveNonCoreActivity().map(activity -> activity == Activity.IDLE).orElse(true);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag compound) {
        super.addAdditionalSaveData(compound);
        CompoundTag emotionsTag = new CompoundTag();
        this.emotions.save(emotionsTag);
        compound.put("Emotions", emotionsTag);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        if (compound.contains("Emotions", 10)) {
            this.emotions.load(compound.getCompound("Emotions"));
        }
    }

    // ===================== Hearing (game-event listener, no traveling particle) =====================

    @Override
    public void updateDynamicGameEventListener(BiConsumer<DynamicGameEventListener<?>, ServerLevel> listenerConsumer) {
        if (this.level() instanceof ServerLevel serverLevel) {
            listenerConsumer.accept(this.dynamicGameEventListener, serverLevel);
        }
    }

    /** Maps an event type and distance to a noise intensity in [0, 1] (closer and louder = stronger). */
    private static float noiseIntensity(Holder<GameEvent> gameEvent, float distance) {
        float falloff = Math.max(0.0f, 1.0f - distance / HEARING_RANGE);
        float base;
        if (gameEvent.is(GameEvent.EXPLODE)) {
            base = 1.0f;
        } else if (gameEvent.is(GameEvent.STEP)) {
            base = 0.25f;
        } else {
            base = 0.6f;
        }
        return base * falloff;
    }

    /**
     * The spider's "ears": a plain game-event listener that hears audible vibrations within range and
     * routes them to curiosity (investigate) or fear (flee). Unlike the full vibration system it spawns
     * no traveling sculk particle, and has no travel delay or wall occlusion (the visible particle was
     * unwanted).
     */
    class SpiderEars implements GameEventListener {
        private final PositionSource source = new EntityPositionSource(MotherSpiderEntity.this, MotherSpiderEntity.this.getEyeHeight());

        @Override
        public PositionSource getListenerSource() {
            return this.source;
        }

        @Override
        public int getListenerRadius() {
            return HEARING_RANGE;
        }

        @Override
        public boolean handleGameEvent(ServerLevel level, Holder<GameEvent> gameEvent, GameEvent.Context context, Vec3 pos) {
            if (MotherSpiderEntity.this.isDeadOrDying() || !gameEvent.is(GameEventTags.WARDEN_CAN_LISTEN)) {
                return false;
            }
            Entity source = context.sourceEntity();
            if (source == MotherSpiderEntity.this) {
                return false;
            }
            // Sneaking suppresses footstep-type vibrations, like vanilla.
            if (source != null && source.isCrouching() && gameEvent.is(GameEventTags.IGNORE_VIBRATIONS_SNEAKING)) {
                return false;
            }
            float distance = (float) pos.distanceTo(MotherSpiderEntity.this.position());
            float intensity = noiseIntensity(gameEvent, distance);
            if (intensity <= 0.0f) {
                return false;
            }
            // Fear from this noise: a close sound she was not already watching makes her jump (a startle,
            // scaled by how close, "not watching" = no line of sight = she did not see it coming), and a
            // loud blast frightens at any range. Either way it marks the source so she can retreat while
            // keeping an eye on it (PANIC's retreat-to-vantage); light/fire fear has no such source and
            // just flees to shadow.
            float fear = 0.0f;
            boolean startled = distance <= STARTLE_RADIUS
                    && !Sight.hasLineOfSight(level, MotherSpiderEntity.this.getEyePosition(), pos);
            if (startled) {
                fear += STARTLE_FEAR_MAX * (1.0f - distance / STARTLE_RADIUS);
            }
            if (intensity >= LOUD_NOISE_INTENSITY) {
                fear += intensity * FEAR_PER_NOISE;
            }

            String action;
            if (fear > 0.0f) {
                MotherSpiderEntity.this.getEmotions().add(EmotionType.FEAR, fear);
                MotherSpiderEntity.this.getBrain().setMemoryWithExpiry(MemoryModuleType.DISTURBANCE_LOCATION, BlockPos.containing(pos), DISTURBANCE_TTL);
                MotherSpiderEntity.this.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                action = startled ? "startle-retreat" : "flee";
            } else {
                // Not frightening: it feeds a little curiosity, and if salient enough (and she is free to)
                // it becomes a spot worth investigating from cover. Faint or distant sounds only cross the
                // bar when she is hungry or already on edge.
                MotherSpiderEntity.this.getEmotions().add(EmotionType.CURIOSITY, intensity * CURIOSITY_PER_NOISE);
                boolean investigate = MotherSpiderEntity.this.isFreeToInvestigate() && MotherSpiderEntity.this.isWorthInvestigating(intensity);
                if (investigate) {
                    MotherSpiderEntity.this.getBrain().setMemoryWithExpiry(MemoryModuleType.DISTURBANCE_LOCATION, BlockPos.containing(pos), DISTURBANCE_TTL);
                    MotherSpiderEntity.this.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
                }
                action = investigate ? "investigate" : "curiosity-only";
            }
            // TEMPORARY debug (remove in P6): confirms what she hears and how she reacts.
            Cauchemar.LOGGER.info("[spider] heard noise: dist={} intensity={} action={}", distance, intensity, action);
            return true;
        }
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
        ClimberPathNavigator<MotherSpiderEntity> navigation = new ClimberPathNavigator<>(this, level);
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

    @Override
    public float getPathingMalus(BlockGetter cache, Mob entity, PathType nodeType, BlockPos pos, Vec3i direction, Predicate<Direction> sides) {
        float base = entity.getPathfindingMalus(nodeType);
        if (base < 0.0F) {
            return base; // already forbidden, leave it
        }
        // Bias every route toward shadow: add a cost proportional to the cell's light, so the spider
        // prefers dark paths and only crosses lit cells when there is no darker way around. This biases
        // the A* cost, it does not forbid light, so a forced crossing stays possible. Light is read from
        // the live level (pathfinding is synchronous on the server thread).
        int light = this.level().getMaxLocalRawBrightness(pos);
        return base + light * LIGHT_PATHING_MALUS_PER_LEVEL;
    }

    // ===================== Climbing: bridges + delegation to the locomotion profile =====================

    // Bridges so ClimberLocomotion can reach protected/super behaviour from outside the entity.
    @Override
    public void climberSuperTravel(Vec3 relative) {
        super.travel(relative);
    }

    @Override
    public void climberUpdateAnimation() {
        this.calculateEntityAnimation(true);
    }

    @Override
    public float climberJumpPower() {
        return this.getJumpPower();
    }

    @Override
    public boolean climberAffectedByFluids() {
        return this.isAffectedByFluids();
    }

    @Override
    public void travel(Vec3 relative) {
        this.locomotion.travel(relative);
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

    @Override
    public void jumpFromGround() {
        if (!this.locomotion.onJump()) {
            super.jumpFromGround();
        }
    }

    @Override
    public Pair<Direction, Vec3> getGroundDirection() {
        return this.locomotion.getGroundDirection();
    }

    @Override
    public Direction getGroundSide() {
        return this.locomotion.getGroundSide();
    }

    @Override
    public boolean isAttachedToSurface() {
        return this.locomotion.isAttachedToSurface();
    }

    @Override
    public float getMovementSpeed() {
        return (float) this.getAttributeValue(Attributes.MOVEMENT_SPEED);
    }

    @Override
    public void setJumpDirection(Vec3 dir) {
        this.locomotion.setJumpDirection(dir);
    }

    @Override
    public void applyMovement(MoveIntent intent) {
        this.locomotion.applyMovement(intent);
    }

    @Override
    public Orientation calculateOrientation(float partialTicks) {
        return this.locomotion.calculateOrientation(partialTicks);
    }

    @Override
    public Orientation getOrientation() {
        return this.locomotion.getOrientation();
    }

    @Override
    public void setRenderOrientation(Orientation orientation) {
        this.locomotion.setRenderOrientation(orientation);
    }

    @Override
    public Orientation getRenderOrientation() {
        return this.locomotion.getRenderOrientation();
    }

    @Override
    public float getVerticalOffset(float partialTicks) {
        return this.locomotion.getVerticalOffset(partialTicks);
    }

    @Override
    public float getAttachmentOffset(Direction.Axis axis, float partialTicks) {
        return this.locomotion.getAttachmentOffset(axis, partialTicks);
    }

    // ===================== Attributes & animation =====================

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 80.0)
                .add(Attributes.MOVEMENT_SPEED, 0.25)
                .add(Attributes.ATTACK_DAMAGE, 6.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 0.5)
                // Wide enough that the shadow search can path to darkness farther away (the
                // pathfinding region and node budget both scale with FOLLOW_RANGE).
                .add(Attributes.FOLLOW_RANGE, 32.0)
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
            // not restart on micro-stops. Walk playback speed is fixed per mode (wander x1/walk x2/sprint x5).
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
        /** Rushing to an urgent objective (fleeing to shadow). */
        SPRINT(1.8, 5.0);

        public final double navSpeedModifier;
        public final double animationSpeed;

        MovementMode(double navSpeedModifier, double animationSpeed) {
            this.navSpeedModifier = navSpeedModifier;
            this.animationSpeed = animationSpeed;
        }

        /**
         * The mode whose nav speed is closest to {@code navSpeed}: maps an active walk target's speed
         * modifier back to a gait so the animation matches the locomotion the behaviors requested.
         */
        public static MovementMode forNavSpeed(double navSpeed) {
            MovementMode closest = WANDER;
            double smallestDelta = Double.MAX_VALUE;
            for (MovementMode mode : values()) {
                double delta = Math.abs(mode.navSpeedModifier - navSpeed);
                if (delta < smallestDelta) {
                    smallestDelta = delta;
                    closest = mode;
                }
            }
            return closest;
        }
    }
}
