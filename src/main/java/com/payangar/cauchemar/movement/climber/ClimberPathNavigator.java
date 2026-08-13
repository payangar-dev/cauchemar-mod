package com.payangar.cauchemar.movement.climber;

import net.minecraft.world.entity.Mob;
import net.minecraft.world.level.Level;

/**
 * The concrete climbing navigator: an {@link AdvancedClimberPathNavigator} preset to place path nodes
 * on walls and ceilings (so a climbing mob deliberately routes onto any climbable surface). Path
 * following is handled by the common {@link com.payangar.cauchemar.movement.Steering} layer; this
 * class only fixes the climbing configuration.
 *
 * <p>Ported from Nyf's Spiders (it was {@code BetterSpiderPathNavigator}). The vanilla MC-94054
 * "keep pushing past the path end" overrun it used to carry was disabled and has been removed: the
 * common Steering layer now owns arrival, so if a "do not stall short of the goal" behaviour is ever
 * wanted it belongs there (driving toward the goal past the last node), not in a navigator override.
 */
public class ClimberPathNavigator<T extends Mob & IClimberEntity> extends AdvancedClimberPathNavigator<T> {

    public ClimberPathNavigator(T entity, Level level) {
        super(entity, level, false, true, true);
    }
}
