package com.payangar.cauchemar.entity.ai.behavior;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.behavior.OneShot;
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.entity.ai.util.LandRandomPos;
import net.minecraft.world.phys.Vec3;

/**
 * Wander biased toward shadow: when the mob has no walk target, it samples a few reachable random land
 * positions and walks to the darkest one, so the creature roams through shadow rather than strolling
 * into the light. A Brain behavior port of the old {@code SeekDarknessGoal} dark-biased wander
 * (modelled on vanilla {@code RandomStroll}); the full fear-driven shadow seeking returns in P3.
 */
public final class DarkBiasedStroll {

    private static final int SAMPLES = 6;
    private static final int MAX_HORIZONTAL = 10;
    private static final int MAX_VERTICAL = 7;

    private DarkBiasedStroll() {
    }

    public static OneShot<PathfinderMob> create(float speedModifier) {
        return BehaviorBuilder.<PathfinderMob>create(
                instance -> instance.group(instance.absent(MemoryModuleType.WALK_TARGET)).apply(instance, walkTarget -> (level, mob, gameTime) -> {
                    Vec3 best = null;
                    int bestLight = Integer.MAX_VALUE;
                    for (int i = 0; i < SAMPLES; i++) {
                        Vec3 candidate = LandRandomPos.getPos(mob, MAX_HORIZONTAL, MAX_VERTICAL);
                        if (candidate == null) {
                            continue;
                        }
                        int light = level.getMaxLocalRawBrightness(BlockPos.containing(candidate));
                        if (light < bestLight) {
                            bestLight = light;
                            best = candidate;
                        }
                        if (light <= 0) {
                            break;
                        }
                    }
                    if (best == null) {
                        return false;
                    }
                    walkTarget.setOrErase(Optional.of(new WalkTarget(best, speedModifier, 0)));
                    return true;
                }));
    }
}
