package com.payangar.cauchemar.entity.ai;

import com.payangar.cauchemar.emotion.EmotionType;
import com.payangar.cauchemar.emotion.Emotions;
import com.payangar.cauchemar.entity.MotherSpiderEntity.MovementMode;
import net.minecraft.util.Mth;

/**
 * How the spider investigates a disturbance, derived from her emotions. This is the single place that
 * turns feelings into investigation parameters, so {@code InvestigateDisturbance} stays about mechanics.
 *
 * <p>Anger makes her bolder and more aggressive: she approaches faster, watches from closer, and for less
 * time. Hunger makes her a touch keener (a slightly closer look). A calm, merely curious spider is slow,
 * keeps her distance, and observes patiently.
 *
 * @param approachSpeed        nav speed modifier for the move to the vantage
 * @param minStandoff          closest she is willing to observe from (blocks)
 * @param maxStandoff          farthest a useful vantage may be (blocks)
 * @param observeDurationTicks how long she watches once in position
 */
public record InvestigationStyle(float approachSpeed, int minStandoff, int maxStandoff, int observeDurationTicks) {

    // Calm baseline (merely curious) at anger 0; aggressive extreme at anger 100.
    private static final float CALM_SPEED = (float) MovementMode.WANDER.navSpeedModifier;
    private static final float ANGRY_SPEED = (float) MovementMode.SPRINT.navSpeedModifier;
    // She watches from a distance: a wide band, so the vantage search aims for the far midpoint but still
    // finds a spot in tighter places rather than giving up. Anger brings her in closer and bolder.
    private static final int CALM_MIN_STANDOFF = 9;
    private static final int CALM_MAX_STANDOFF = 18;
    private static final int ANGRY_MIN_STANDOFF = 5;
    private static final int ANGRY_MAX_STANDOFF = 11;
    private static final int CALM_OBSERVE_TICKS = 100;
    private static final int ANGRY_OBSERVE_TICKS = 30;
    /** Hunger pulls the vantage in by at most this many blocks (a keener look when starving). */
    private static final int HUNGER_STANDOFF_PULL = 3;

    public static InvestigationStyle from(Emotions emotions) {
        float anger = emotions.get(EmotionType.ANGER) / 100.0f;
        float hunger = emotions.get(EmotionType.HUNGER) / 100.0f;

        float speed = Mth.lerp(anger, CALM_SPEED, ANGRY_SPEED);
        int minStandoff = Math.round(Mth.lerp(anger, CALM_MIN_STANDOFF, ANGRY_MIN_STANDOFF));
        int maxStandoff = Math.round(Mth.lerp(anger, CALM_MAX_STANDOFF, ANGRY_MAX_STANDOFF)) - Math.round(hunger * HUNGER_STANDOFF_PULL);
        maxStandoff = Math.max(maxStandoff, minStandoff + 1);
        int observeDurationTicks = Math.round(Mth.lerp(anger, CALM_OBSERVE_TICKS, ANGRY_OBSERVE_TICKS));

        return new InvestigationStyle(speed, minStandoff, maxStandoff, observeDurationTicks);
    }
}
