package com.payangar.cauchemar.entity.ai.behavior;

import com.payangar.cauchemar.entity.MotherSpiderEntity;
import com.payangar.cauchemar.perception.VantagePointFinder;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.behavior.BlockPosTracker;
import net.minecraft.world.entity.ai.behavior.OneShot;
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.phys.Vec3;

/**
 * Fearful retreat that keeps the threat in sight. When the spider is afraid of a known source
 * ({@code DISTURBANCE_LOCATION}), she sprints to a vantage cell at a safe distance that still has line of
 * sight to it, facing it as she goes: she never turns her back and bolts blindly, she repositions to
 * somewhere safe from which she can keep watching. If no such safe vantage exists she yields (returns
 * false) so the plain flight to shadow ({@code SeekShadow}) can take over.
 *
 * <p>Runs in {@code PANIC}, ordered before {@code SeekShadow}. As a one-shot it re-decides only when the
 * walk target frees up (on arrival), so it does not thrash the path. The sustained watching afterwards is
 * emergent: once she reaches safety she leaves the danger radius, fear decays, and she drops into
 * {@code INVESTIGATE} to observe the same disturbance from cover.
 */
public final class RetreatToSafeVantage {

    private RetreatToSafeVantage() {
    }

    public static OneShot<MotherSpiderEntity> create(float speed, int minStandoff, int maxStandoff, int arrivalDist) {
        return BehaviorBuilder.<MotherSpiderEntity>create(instance -> instance.group(
                instance.absent(MemoryModuleType.WALK_TARGET),
                instance.present(MemoryModuleType.DISTURBANCE_LOCATION),
                instance.registered(MemoryModuleType.LOOK_TARGET)
        ).apply(instance, (walkTarget, disturbance, lookTarget) -> (level, mob, gameTime) -> {
            BlockPos threat = instance.get(disturbance);
            int reach = (int) mob.getAttributeValue(Attributes.FOLLOW_RANGE);
            Vec3 awayFromThreat = awayDirection(mob.blockPosition(), threat);
            Optional<BlockPos> vantage = VantagePointFinder.find(level, mob.blockPosition(), threat,
                    minStandoff, maxStandoff, reach, awayFromThreat);
            if (vantage.isEmpty()) {
                return false; // nowhere safe to watch from -> let SeekShadow flee to darkness instead
            }
            walkTarget.setOrErase(Optional.of(new WalkTarget(Vec3.atCenterOf(vantage.get()), speed, arrivalDist)));
            lookTarget.set(new BlockPosTracker(threat));
            return true;
        }));
    }

    /** Unit direction from the threat toward the spider (so a retreat vantage lies away from the threat),
     *  or {@code null} when she is essentially on top of it and no direction is meaningful. */
    private static Vec3 awayDirection(BlockPos spiderPos, BlockPos threat) {
        Vec3 delta = Vec3.atCenterOf(spiderPos).subtract(Vec3.atCenterOf(threat));
        return delta.lengthSqr() < 1.0e-4 ? null : delta.normalize();
    }
}
