package com.payangar.cauchemar.emotion;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.util.Mth;

/**
 * A creature's emotional state: one scalar drive per {@link EmotionType}, each clamped to [0, 100].
 *
 * <p>Generic integrator, shared by every creature: each tick {@link #tick()} drifts every value toward
 * its rest value, and the creature's appraisal adds stimulus spikes on top with {@link #add}. AI reads
 * the drives with {@link #get} / {@link #atLeast} to gate behaviour. The state persists across save and
 * load via {@link #save} / {@link #load}.
 *
 * <p>Not wired to any creature yet (built in P0; consumed from P3). Holds no Minecraft state beyond the
 * raw values, so it is trivially serialisable and testable.
 */
public final class Emotions {

    private static final float MIN = 0.0f;
    private static final float MAX = 100.0f;

    private final float[] values = new float[EmotionType.VALUES.length];

    /** Adds (or, if negative, removes) a stimulus to an emotion, clamped to [0, 100]. */
    public void add(EmotionType type, float delta) {
        this.values[type.ordinal()] = Mth.clamp(this.values[type.ordinal()] + delta, MIN, MAX);
    }

    /** Sets an emotion outright (e.g. feeding resets hunger to 0), clamped to [0, 100]. */
    public void set(EmotionType type, float value) {
        this.values[type.ordinal()] = Mth.clamp(value, MIN, MAX);
    }

    public float get(EmotionType type) {
        return this.values[type.ordinal()];
    }

    public boolean atLeast(EmotionType type, float threshold) {
        return this.values[type.ordinal()] >= threshold;
    }

    /** Drifts every emotion toward its rest value. Call once per tick before the appraisal reads them. */
    public void tick() {
        for (EmotionType type : EmotionType.VALUES) {
            float value = this.values[type.ordinal()];
            float rest = type.restValue;
            float step = type.driftPerTick;
            if (value < rest) {
                value = Math.min(rest, value + step);
            } else if (value > rest) {
                value = Math.max(rest, value - step);
            }
            this.values[type.ordinal()] = value;
        }
    }

    public void save(CompoundTag tag) {
        for (EmotionType type : EmotionType.VALUES) {
            tag.putFloat(type.name(), this.values[type.ordinal()]);
        }
    }

    public void load(CompoundTag tag) {
        for (EmotionType type : EmotionType.VALUES) {
            if (tag.contains(type.name())) {
                this.values[type.ordinal()] = Mth.clamp(tag.getFloat(type.name()), MIN, MAX);
            }
        }
    }
}
