package com.payangar.cauchemar.emotion;

/**
 * The drives a creature can feel. Each emotion is a scalar in [0, 100] (see {@link Emotions}) with its
 * own resting dynamics: between stimuli the value drifts toward {@link #restValue} by at most
 * {@link #driftPerTick} per tick.
 *
 * <ul>
 *   <li>{@link #HUNGER} rests at 100, so it only rises over time (reset by feeding); it drives prey search.</li>
 *   <li>{@link #EXCITEMENT}, {@link #ANGER}, {@link #FEAR}, {@link #CURIOSITY} rest at 0, so they fade
 *       when their stimulus stops.</li>
 * </ul>
 *
 * <p>Values and rates here are initial placeholders, tuned in-game (P6). This component is generic and
 * shared by every creature; which emotions a creature actually uses is decided by its appraisal.
 */
public enum EmotionType {
    /** Need to feed. Drifts up over time; reset on feeding. Triggers prey search when high. */
    HUNGER(100.0f, 0.01f),
    /** Predatory build-up while stalking unseen prey. At a threshold it enables the attack. */
    EXCITEMENT(0.0f, 0.2f),
    /** Rises when the creature is hurt. At a threshold it also enables the attack. */
    ANGER(0.0f, 0.1f),
    /** Rises near fire, light and explosions. Drives fleeing; dominated by excitement/anger when high. */
    FEAR(0.0f, 2.0f),
    /** Rises with moderate noise. Drives investigating the source at a distance. */
    CURIOSITY(0.0f, 0.3f);

    public static final EmotionType[] VALUES = values();

    /** The value the emotion drifts toward when no stimulus acts on it. */
    public final float restValue;
    /** The maximum the value moves toward {@link #restValue} per tick (linear charge/decay). */
    public final float driftPerTick;

    EmotionType(float restValue, float driftPerTick) {
        this.restValue = restValue;
        this.driftPerTick = driftPerTick;
    }
}
