package com.payangar.cauchemar.entity.ai.behavior;

import com.google.common.collect.ImmutableMap;
import com.payangar.cauchemar.Cauchemar;
import com.payangar.cauchemar.emotion.EmotionType;
import com.payangar.cauchemar.entity.MotherSpiderEntity;
import com.payangar.cauchemar.entity.ai.InvestigationStyle;
import com.payangar.cauchemar.perception.Sight;
import com.payangar.cauchemar.perception.VantagePointFinder;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.behavior.Behavior;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.schedule.Activity;
import net.minecraft.world.phys.Vec3;

/**
 * Cautious, vicious investigation of a heard disturbance, as a small phase machine. Instead of walking
 * onto the noise, the spider turns to face it, repositions to a vantage point that has line of sight at a
 * standoff distance (preferring shadow, and happy to watch from a wall or ceiling), observes to see what
 * it was, then decides. Every phase gives up gracefully (nowhere to watch from, cannot reach, nothing to
 * see) so she never freezes on an unreachable or empty noise.
 *
 * <p>How she investigates is shaped by her emotions through {@link InvestigationStyle}: anger makes her
 * faster, closer and briefer; hunger makes her keener. The decision at the end is the seam for the future
 * hunt (perceived prey + hungry): today it only records the outcome.
 *
 * <p>Lives in its own {@code INVESTIGATE} activity and keeps running only while INVESTIGATE stays the
 * chosen activity, so anything higher priority (fear -> PANIC, later a hunt) preempts it cleanly. The
 * Brain does not stop a deactivated activity's behaviors for us, so {@link #canStillUse} checks it.
 */
public class InvestigateDisturbance extends Behavior<MotherSpiderEntity> {

    private enum Phase { ORIENT, REPOSITION, OBSERVE, DONE }

    /** Hard safety cap on one investigation, and the disturbance memory lifetime once she commits. */
    private static final int OVERALL_TIMEOUT_TICKS = 600;
    /** Ticks spent turning to face the source before deciding whether to reposition. */
    private static final int ORIENT_TICKS = 15;
    /** How near the vantage counts as arrived (also the walk target's close-enough distance). */
    private static final int VANTAGE_ARRIVAL_DIST = 2;
    /** Throttle (ticks) for the line-of-sight and entity scans while travelling and observing. */
    private static final int SCAN_INTERVAL = 5;
    /** How far around the disturbance she looks for what made the noise. */
    private static final double OBSERVE_SCAN_RADIUS = 4.0;
    /** At or above this hunger a sighting would become a hunt (the future P4/P5 seam). */
    private static final float HUNT_HUNGER_THRESHOLD = 60.0f;

    private Phase phase = Phase.DONE;
    private BlockPos disturbance;
    private InvestigationStyle style;
    private int phaseTicks;
    private boolean sawCreature;

    public InvestigateDisturbance() {
        super(ImmutableMap.of(
                MemoryModuleType.DISTURBANCE_LOCATION, MemoryStatus.VALUE_PRESENT,
                MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED,
                MemoryModuleType.LOOK_TARGET, MemoryStatus.REGISTERED,
                MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE, MemoryStatus.REGISTERED
        ), OVERALL_TIMEOUT_TICKS, OVERALL_TIMEOUT_TICKS);
    }

    @Override
    protected boolean canStillUse(ServerLevel level, MotherSpiderEntity spider, long gameTime) {
        return this.phase != Phase.DONE
                && spider.getBrain().getActiveNonCoreActivity().filter(activity -> activity == Activity.INVESTIGATE).isPresent();
    }

    @Override
    protected void start(ServerLevel level, MotherSpiderEntity spider, long gameTime) {
        Optional<BlockPos> heard = spider.getBrain().getMemory(MemoryModuleType.DISTURBANCE_LOCATION);
        if (heard.isEmpty()) {
            this.phase = Phase.DONE;
            return;
        }
        this.disturbance = heard.get();
        this.style = InvestigationStyle.from(spider.getEmotions());
        this.sawCreature = false;
        this.phase = Phase.ORIENT;
        this.phaseTicks = ORIENT_TICKS;
        // Commit: hold the disturbance for the whole investigation (the raw noise memory is short-lived),
        // and turn to face it at once. We clear it ourselves when the investigation ends.
        spider.getBrain().setMemoryWithExpiry(MemoryModuleType.DISTURBANCE_LOCATION, this.disturbance, OVERALL_TIMEOUT_TICKS);
        // Face it from a standstill: drop any leftover walk target (e.g. a fearful retreat she was
        // finishing as she calmed) so ORIENT is a stationary turn before she decides to reposition.
        spider.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        lookAtDisturbance(spider);
    }

    @Override
    protected void tick(ServerLevel level, MotherSpiderEntity spider, long gameTime) {
        // Keep her gaze on the source throughout (CORE's LookAtTargetSink executes it).
        lookAtDisturbance(spider);
        switch (this.phase) {
            case ORIENT -> tickOrient(level, spider);
            case REPOSITION -> tickReposition(spider);
            case OBSERVE -> tickObserve(level, spider, gameTime);
            case DONE -> { }
        }
    }

    private void tickOrient(ServerLevel level, MotherSpiderEntity spider) {
        if (--this.phaseTicks > 0) {
            return;
        }
        // Facing done. She only watches from where she stands if she is already at a good distance AND
        // can see the source; if she is too close (the common case), too far, or blind, she repositions
        // to a proper faraway vantage. This is what stops her from observing point-blank.
        double distance = Math.sqrt(spider.blockPosition().distSqr(this.disturbance));
        boolean wellPlaced = distance >= this.style.minStandoff() && distance <= this.style.maxStandoff()
                && hasLineOfSightToDisturbance(level, spider);
        if (wellPlaced) {
            beginObserve(spider);
            return;
        }
        // Find a vantage with line of sight at a standoff distance; give up if there is none (a cautious
        // spider does not close in blindly on a noise she cannot get a distant view of).
        int reach = (int) spider.getAttributeValue(Attributes.FOLLOW_RANGE);
        Optional<BlockPos> vantage = VantagePointFinder.find(level, spider.blockPosition(), this.disturbance,
                this.style.minStandoff(), this.style.maxStandoff(), reach);
        if (vantage.isEmpty()) {
            finish(spider);
            return;
        }
        this.phase = Phase.REPOSITION;
        spider.getBrain().setMemory(MemoryModuleType.WALK_TARGET,
                new WalkTarget(Vec3.atCenterOf(vantage.get()), this.style.approachSpeed(), VANTAGE_ARRIVAL_DIST));
    }

    private void tickReposition(MotherSpiderEntity spider) {
        // She walks all the way to the chosen faraway vantage (which has line of sight by construction);
        // she does not settle for a closer view on the way there. MoveToTargetSink (CORE) drives there
        // and clears WALK_TARGET on arrival or failure.
        if (spider.getBrain().hasMemoryValue(MemoryModuleType.WALK_TARGET)) {
            return;
        }
        if (spider.getBrain().hasMemoryValue(MemoryModuleType.CANT_REACH_WALK_TARGET_SINCE)) {
            finish(spider); // cannot get to any vantage; do not close in on the source
        } else {
            beginObserve(spider); // arrived at the vantage
        }
    }

    private void tickObserve(ServerLevel level, MotherSpiderEntity spider, long gameTime) {
        if (!this.sawCreature && gameTime % SCAN_INTERVAL == 0) {
            List<LivingEntity> seen = Sight.scanVisibleLiving(level, spider, this.disturbance, OBSERVE_SCAN_RADIUS);
            this.sawCreature = !seen.isEmpty();
        }
        if (--this.phaseTicks <= 0) {
            decideAndFinish(spider);
        }
    }

    private void beginObserve(MotherSpiderEntity spider) {
        this.phase = Phase.OBSERVE;
        this.phaseTicks = this.style.observeDurationTicks();
        // Hold position and watch: stop walking (also frees the WALK_TARGET window for the next behavior).
        spider.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
    }

    private void decideAndFinish(MotherSpiderEntity spider) {
        boolean hungry = spider.getEmotions().atLeast(EmotionType.HUNGER, HUNT_HUNGER_THRESHOLD);
        // TODO(P4/P5): perceived creature + hungry -> begin the hunt (STALK activity). No hunt exists yet.
        // TEMPORARY debug (remove with the hearing debug in P6): what the observation concluded.
        Cauchemar.LOGGER.info("[spider] investigation done: sawCreature={} hungry={} -> {}",
                this.sawCreature, hungry, this.sawCreature && hungry ? "would hunt (not implemented)" : "resume idle");
        finish(spider);
    }

    /** Ends the investigation: forget the disturbance so the activity yields back to idle. */
    private void finish(MotherSpiderEntity spider) {
        spider.getBrain().eraseMemory(MemoryModuleType.DISTURBANCE_LOCATION);
        this.phase = Phase.DONE;
    }

    private void lookAtDisturbance(MotherSpiderEntity spider) {
        spider.getBrain().setMemory(MemoryModuleType.LOOK_TARGET, new BlockPosTracker(this.disturbance));
    }

    private boolean hasLineOfSightToDisturbance(ServerLevel level, MotherSpiderEntity spider) {
        return Sight.hasLineOfSight(level, spider.getEyePosition(), Vec3.atCenterOf(this.disturbance));
    }

    @Override
    protected void stop(ServerLevel level, MotherSpiderEntity spider, long gameTime) {
        // Tidy up so idle resumes cleanly (WALK_TARGET is also in the activity's erase-on-stop set).
        spider.getBrain().eraseMemory(MemoryModuleType.WALK_TARGET);
        spider.getBrain().eraseMemory(MemoryModuleType.LOOK_TARGET);
        this.phase = Phase.DONE;
        this.disturbance = null;
        this.style = null;
    }
}
