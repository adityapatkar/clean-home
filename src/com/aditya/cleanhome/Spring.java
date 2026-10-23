package com.aditya.cleanhome;

import android.animation.TimeInterpolator;

/**
 * Critically-under-damped spring, normalised so it settles at t = 1. This is the
 * curve behind iOS/tvOS's "springy but calm" motion: a quick start, a few percent
 * of overshoot, and a soft landing - unlike OvershootInterpolator's hard snap-back.
 */
final class Spring implements TimeInterpolator {

    /** Menus and sheets: ~4% overshoot. */
    static final Spring GENTLE = new Spring(0.72f);
    /** Focus lift: a touch livelier, ~7% overshoot. */
    static final Spring LIVELY = new Spring(0.62f);

    private final float zeta, omega, damped;

    private Spring(float dampingRatio) {
        zeta = dampingRatio;
        // Chosen so the envelope e^(-zeta*omega*t) is ~0.1% by t = 1.
        omega = 6.9f / dampingRatio;
        damped = (float) (omega * Math.sqrt(1 - zeta * zeta));
    }

    @Override
    public float getInterpolation(float t) {
        if (t >= 1f) return 1f;
        double decay = Math.exp(-zeta * omega * t);
        return (float) (1 - decay * (Math.cos(damped * t) + (zeta * omega / damped) * Math.sin(damped * t)));
    }
}
