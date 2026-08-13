package com.payangar.cauchemar.entity.ai.behavior;

import com.payangar.cauchemar.emotion.EmotionType;
import com.payangar.cauchemar.entity.MotherSpiderEntity;
import com.payangar.cauchemar.perception.SurfaceUtil;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.behavior.OneShot;
import net.minecraft.world.entity.ai.behavior.declarative.BehaviorBuilder;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.phys.Vec3;

/**
 * Fear-driven flight to shadow: while {@link EmotionType#FEAR} is above a threshold, the spider heads
 * to the nearest cell it can cling to (floor, wall or ceiling) that is dark enough, found by a budgeted
 * directed flood over reachable surfaces that minimises light. This is the Brain port of the old
 * {@code SeekDarknessGoal}, now gated by the emotion instead of being always-on. Light (and later fire,
 * explosions) raise fear in the entity's appraisal; reaching shadow lets fear decay and the spider
 * calms back to idle.
 */
public final class SeekShadow {

    private static final int MAX_SEARCH_NODES = 2048;

    private SeekShadow() {
    }

    public static OneShot<MotherSpiderEntity> create(float speedModifier, float fearThreshold, int lightThreshold, int searchRadius) {
        return BehaviorBuilder.<MotherSpiderEntity>create(
                instance -> instance.group(instance.absent(MemoryModuleType.WALK_TARGET)).apply(instance, walkTarget -> (level, mob, gameTime) -> {
                    if (!mob.getEmotions().atLeast(EmotionType.FEAR, fearThreshold)) {
                        return false;
                    }
                    BlockPos dark = searchDarkness(level, mob.blockPosition(), lightThreshold, searchRadius);
                    if (dark == null) {
                        return false;
                    }
                    walkTarget.setOrErase(Optional.of(new WalkTarget(Vec3.atCenterOf(dark), speedModifier, 1)));
                    return true;
                }));
    }

    /**
     * Budgeted flood over cling-able cells (passable + at least one solid face) from the origin,
     * minimising light. Returns the nearest cell at or below {@code lightThreshold}, otherwise the
     * darkest reachable cell strictly darker than the origin, otherwise {@code null}.
     */
    private static BlockPos searchDarkness(ServerLevel level, BlockPos origin, int lightThreshold, int searchRadius) {
        int currentLight = SurfaceUtil.rawLight(level, origin);

        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<Long> visited = new HashSet<>();
        queue.add(origin);
        visited.add(origin.asLong());

        BlockPos darkest = null;
        int darkestLight = currentLight;
        int budget = MAX_SEARCH_NODES;

        while (!queue.isEmpty() && budget-- > 0) {
            BlockPos cell = queue.poll();
            int light = SurfaceUtil.rawLight(level, cell);

            if (light <= lightThreshold) {
                return cell;
            }
            if (light < darkestLight) {
                darkestLight = light;
                darkest = cell;
            }

            for (Direction dir : Direction.values()) {
                BlockPos next = cell.relative(dir);
                if (Math.abs(next.getX() - origin.getX()) > searchRadius
                        || Math.abs(next.getY() - origin.getY()) > searchRadius
                        || Math.abs(next.getZ() - origin.getZ()) > searchRadius) {
                    continue;
                }
                if (visited.add(next.asLong()) && SurfaceUtil.canCling(level, next)) {
                    queue.add(next);
                }
            }
        }
        return darkest;
    }
}
