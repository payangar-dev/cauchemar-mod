package com.payangar.cauchemar.entity.climber;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * A path node the climbing entity is heading toward, paired with the surface side it should walk on
 * once there. Tracked while pathing so the renderer/AI can anticipate the upcoming surface. Ported
 * from Nyf's Spiders.
 */
public class PathingTarget {
    public final BlockPos pos;
    public final Direction side;

    public PathingTarget(BlockPos pos, Direction side) {
        this.pos = pos;
        this.side = side;
    }
}
