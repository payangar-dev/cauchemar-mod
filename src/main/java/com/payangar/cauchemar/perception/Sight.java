package com.payangar.cauchemar.perception;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

/**
 * Minimal line-of-sight primitives, the seed of the perception layer (ADR-5). Scoped to what the
 * investigation needs (can a point see another point, which living entities near a spot are visible);
 * the full Vision/Acuity model (light and movement acuities, detection confidence) comes with the hunt.
 * Generic: takes any {@link LivingEntity} observer, no coupling to a specific creature.
 */
public final class Sight {

    private Sight() {
    }

    /**
     * Whether blocks leave a clear line between two world points (fluids ignored). Same test as
     * {@link LivingEntity#hasLineOfSight}, but point to point: a block collision means occluded.
     */
    public static boolean hasLineOfSight(Level level, Vec3 from, Vec3 to) {
        return level.clip(new ClipContext(from, to, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, CollisionContext.empty()))
                .getType() == HitResult.Type.MISS;
    }

    /**
     * The living entities within {@code radius} of {@code around} that the observer can actually see
     * (clear line of sight from its eyes to theirs), excluding the observer itself. Returned unfiltered
     * beyond visibility so the caller decides what counts as prey. The AABB scan bounds the cost; the
     * line-of-sight raycast runs only on the few entities the box already selected.
     */
    public static List<LivingEntity> scanVisibleLiving(Level level, LivingEntity observer, BlockPos around, double radius) {
        Vec3 eye = observer.getEyePosition();
        AABB box = new AABB(around).inflate(radius);
        return level.getEntitiesOfClass(LivingEntity.class, box,
                target -> target != observer && target.isAlive() && hasLineOfSight(level, eye, target.getEyePosition()));
    }
}
