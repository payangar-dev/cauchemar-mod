package com.payangar.cauchemar.entity.ai;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.mojang.datafixers.util.Pair;
import com.mojang.serialization.Dynamic;
import com.payangar.cauchemar.emotion.EmotionType;
import com.payangar.cauchemar.entity.MotherSpiderEntity;
import com.payangar.cauchemar.entity.ai.behavior.DarkBiasedStroll;
import com.payangar.cauchemar.entity.ai.behavior.InvestigateDisturbance;
import com.payangar.cauchemar.entity.ai.behavior.RetreatToSafeVantage;
import com.payangar.cauchemar.entity.ai.behavior.SeekShadow;
import java.util.List;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.behavior.BehaviorControl;
import net.minecraft.world.entity.ai.behavior.DoNothing;
import net.minecraft.world.entity.ai.behavior.LookAtTargetSink;
import net.minecraft.world.entity.ai.behavior.MoveToTargetSink;
import net.minecraft.world.entity.ai.behavior.RunOne;
import net.minecraft.world.entity.ai.behavior.SetEntityLookTarget;
import net.minecraft.world.entity.ai.behavior.Swim;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.sensing.Sensor;
import net.minecraft.world.entity.ai.sensing.SensorType;
import net.minecraft.world.entity.schedule.Activity;

/**
 * Brain wiring for the Mother Spider (P1 skeleton), modelled on {@code WardenAi}.
 *
 * <p>Behaviour baseline equivalent to the old goals: it floats, looks at the player, and wanders
 * biased toward shadow. Built on the vanilla Brain system so perception (P2), emotions and the hunt
 * FSM (P3+) layer on top. Movement still flows through the climbing navigation: {@code MoveToTargetSink}
 * consumes {@code WALK_TARGET} and drives the entity's {@code ClimberPathNavigator}.
 */
public final class SpiderAi {

    /** Wander navigation speed: matches {@code MotherSpiderEntity.MovementMode.WANDER.navSpeedModifier}. */
    private static final float WANDER_SPEED = 0.6f;

    /** Distance band (blocks) for the fearful retreat vantage: far enough to feel safe, still seeing the
     *  threat. Larger than the danger radius that sustains fear (and than the investigate standoff), so
     *  reaching it lets her calm down and then observe from cover. */
    private static final int SAFE_MIN_STANDOFF = 14;
    private static final int SAFE_MAX_STANDOFF = 22;

    static final List<SensorType<? extends Sensor<? super MotherSpiderEntity>>> SENSOR_TYPES = List.of(
            SensorType.NEAREST_LIVING_ENTITIES
    );

    static final List<MemoryModuleType<?>> MEMORY_TYPES = List.of(
            MemoryModuleType.WALK_TARGET,
            MemoryModuleType.LOOK_TARGET,
            MemoryModuleType.PATH,
            MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE,
            MemoryModuleType.NEAREST_LIVING_ENTITIES,
            MemoryModuleType.NEAREST_VISIBLE_LIVING_ENTITIES,
            MemoryModuleType.DISTURBANCE_LOCATION,
            MemoryModuleType.ATTACK_TARGET
    );

    private SpiderAi() {
    }

    public static Brain<?> makeBrain(MotherSpiderEntity spider, Dynamic<?> dynamic) {
        Brain.Provider<MotherSpiderEntity> provider = Brain.provider(MEMORY_TYPES, SENSOR_TYPES);
        Brain<MotherSpiderEntity> brain = provider.makeBrain(dynamic);
        initCoreActivity(brain);
        initIdleActivity(brain);
        initInvestigateActivity(brain);
        initPanicActivity(brain);
        brain.setCoreActivities(ImmutableSet.of(Activity.CORE));
        brain.setDefaultActivity(Activity.IDLE);
        brain.useDefaultActivity();
        return brain;
    }

    /**
     * Picks the highest-priority valid activity each tick (the FSM switch). Fear wins outright: when
     * afraid she flees ({@code PANIC}); otherwise she investigates a remembered disturbance
     * ({@code INVESTIGATE}, valid only while {@code DISTURBANCE_LOCATION} is set) and falls back to
     * {@code IDLE}. Each switch drops the outgoing activity's {@code WALK_TARGET} (see the erase-on-stop
     * registration below), so a higher-priority drive preempts the current move at once instead of
     * finishing it. The order is where the remaining drives will slot in as the FSM grows (P4/P5: ATTACK,
     * STALK, SEARCH between PANIC and INVESTIGATE).
     */
    public static void updateActivity(MotherSpiderEntity spider) {
        boolean afraid = spider.getEmotions().atLeast(EmotionType.FEAR, MotherSpiderEntity.FLEE_FEAR_THRESHOLD);
        spider.getBrain().setActiveActivityToFirstValid(
                afraid ? ImmutableList.of(Activity.PANIC, Activity.IDLE)
                       : ImmutableList.of(Activity.INVESTIGATE, Activity.IDLE)
        );
    }

    private static void initCoreActivity(Brain<MotherSpiderEntity> brain) {
        brain.addActivity(Activity.CORE, 0, ImmutableList.of(
                new Swim(0.8F),
                new LookAtTargetSink(45, 90),
                new MoveToTargetSink()
        ));
    }

    private static void initIdleActivity(Brain<MotherSpiderEntity> brain) {
        // IDLE erases WALK_TARGET when it stops. The Brain wipes an outgoing activity's "erase when
        // stopped" memories on every activity switch (Brain#eraseMemoriesForOtherActivitesThan), so when
        // IDLE yields to PANIC the half-finished slow wander target is dropped and the flight to shadow
        // can claim a fresh sprint target immediately. We register it through the low-level overload
        // (rather than addActivityAndRemoveMemoryWhenStopped) because that helper would also require
        // WALK_TARGET to be present for IDLE to be valid, whereas IDLE must stay valid with no target.
        brain.addActivityAndRemoveMemoriesWhenStopped(
                Activity.IDLE,
                priorityPairs(10, ImmutableList.of(
                        DarkBiasedStroll.create(WANDER_SPEED),
                        // Glance at a nearby player only now and then, not a constant stare: the look is
                        // picked occasionally against do-nothing pauses (weight 1 vs 3), and
                        // LookAtTargetSink (CORE) holds each glance ~45-90 ticks before releasing it.
                        new RunOne<LivingEntity>(ImmutableList.of(
                                Pair.of(SetEntityLookTarget.create(EntityType.PLAYER, 8.0F), 1),
                                Pair.of(new DoNothing(60, 120), 3)
                        ))
                )),
                ImmutableSet.of(),
                ImmutableSet.<MemoryModuleType<?>>of(MemoryModuleType.WALK_TARGET)
        );
    }

    /**
     * Cautious investigation lives in its own activity so it never competes with the idle wander: while
     * she investigates, the stroll is not running, so she cannot drift off mid-investigation. Valid only
     * while a disturbance is remembered ({@code DISTURBANCE_LOCATION} present), and erases
     * {@code WALK_TARGET} on stop so a higher-priority switch (fear -> PANIC) preempts the reposition
     * instantly, exactly like IDLE. The single {@link InvestigateDisturbance} behavior runs the whole
     * orient -> reposition -> observe -> decide cycle.
     */
    private static void initInvestigateActivity(Brain<MotherSpiderEntity> brain) {
        brain.addActivityAndRemoveMemoriesWhenStopped(
                Activity.INVESTIGATE,
                priorityPairs(10, ImmutableList.of(new InvestigateDisturbance())),
                ImmutableSet.of(Pair.<MemoryModuleType<?>, MemoryStatus>of(MemoryModuleType.DISTURBANCE_LOCATION, MemoryStatus.VALUE_PRESENT)),
                ImmutableSet.<MemoryModuleType<?>>of(MemoryModuleType.WALK_TARGET)
        );
    }

    /**
     * Fear response, in its own activity so it never competes with the idle wander. Two behaviors, in
     * order: if she is afraid of a known source (a close startle, a blast), {@link RetreatToSafeVantage}
     * backs her off to a safe vantage that still sees it; otherwise (ambient dread from light or fire, or
     * no safe vantage) {@link SeekShadow} flees to the nearest darkness. Reuses the vanilla {@code PANIC}
     * key (semantically "flee in fear"); P4/P5 may add dedicated STALK/SEARCH activities.
     */
    private static void initPanicActivity(Brain<MotherSpiderEntity> brain) {
        float sprint = (float) MotherSpiderEntity.MovementMode.SPRINT.navSpeedModifier;
        brain.addActivity(Activity.PANIC, 10, ImmutableList.of(
                RetreatToSafeVantage.create(sprint, SAFE_MIN_STANDOFF, SAFE_MAX_STANDOFF, 2),
                SeekShadow.create(sprint, MotherSpiderEntity.FLEE_FEAR_THRESHOLD, 0, 32)
        ));
    }

    /**
     * Pairs each behavior with an ascending priority starting at {@code priorityStart}, mirroring the
     * Brain's own (package-private) {@code createPriorityPairs}, so we can call the low-level
     * {@code addActivityAndRemoveMemoriesWhenStopped} overload from outside the Brain package.
     */
    private static ImmutableList<? extends Pair<Integer, ? extends BehaviorControl<? super MotherSpiderEntity>>> priorityPairs(
            int priorityStart, ImmutableList<? extends BehaviorControl<? super MotherSpiderEntity>> tasks) {
        ImmutableList.Builder<Pair<Integer, ? extends BehaviorControl<? super MotherSpiderEntity>>> builder = ImmutableList.builder();
        int priority = priorityStart;
        for (BehaviorControl<? super MotherSpiderEntity> task : tasks) {
            builder.add(Pair.of(priority++, task));
        }
        return builder.build();
    }
}
