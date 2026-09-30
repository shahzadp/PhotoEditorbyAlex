package com.alex.photoeditor.ui.components

import kotlin.math.abs

/**
 * Rotation handling for pinch gestures, shared by the image and text stages.
 *
 * Why the stiffness is not uniform
 * -------------------------------
 * The previous version damped *every* rotation by the same factor, which made
 * a free turn feel like it was moving through syrup. But the reason for any
 * stiffness at all is specific: right angles are the positions people actually
 * want to hit. A photo at 7 degrees is "nearly straight" and looks like a
 * mistake; a photo at 90 degrees is deliberate and looks correct. That
 * asymmetry is the whole justification - so the stiffness belongs at those four
 * angles and nowhere else.
 *
 * Damping all the way round threw away the free-form rotation that makes a
 * pinch feel good, in order to buy precision at four specific targets.
 *
 * What this does instead
 * ----------------------
 * Rotation between the detents passes straight through, 1:1 with the fingers.
 * Approaching a right angle a soft pull takes hold and holds the object there
 * until the fingers move decisively past it, so the angle is easy to land and
 * hard to drift off. Past it, the pull releases cleanly into free rotation
 * again, so the user is never fighting resistance in the middle of a turn.
 *
 * The detent is narrow and captures the angle rather than refusing the
 * gesture - a large deliberate move is never blocked, only a small one is
 * slowed. That is what stops it feeling like the object is stuck.
 *
 * Mutable and remembered per gesture, so the image and text stages cannot
 * drift apart in behaviour the way their duplicated arithmetic had.
 */
class RotationSmoother(
    /**
     * Fraction of each frame's rotation applied *at the centre* of a detent.
     *
     * Low, because the point is to resist rather than to follow.
     */
    private val detentDamping: Float = DETENT_DAMPING,
    /**
     * Per-frame wobble ignored everywhere.
     *
     * Small: this is only fingertip tremor, not the stiff dead zone the
     * previous version used. Free rotation must not feel notchy.
     */
    private val deadZoneDegrees: Float = DEAD_ZONE_DEGREES,
    /** Half-width of a detent, in degrees either side of a right angle. */
    private val detentRange: Float = DETENT_RANGE,
    /**
     * Half-width of the fully stiff part of a detent.
     *
     * Kept small on purpose: the hold should be a precise snap at the angle,
     * not a broad zone of resistance the user has to drag through.
     */
    private val detentCore: Float = DETENT_CORE
) {

    /** Accumulated rotation in degrees. */
    var degrees: Float = 0f
        private set

    /**
     * Folds one frame's raw [deltaDegrees] into [degrees].
     *
     * @return the new absolute rotation, folded into (-180, 180].
     */
    fun onDelta(deltaDegrees: Float): Float {
        if (!deltaDegrees.isFinite()) return degrees
        if (abs(deltaDegrees) < deadZoneDegrees) return degrees

        val proposed = wrapDegrees(degrees + deltaDegrees)

        // Inside a detent, move slowly. Outside one, follow the fingers
        // exactly. Decided on where the object *is* rather than where it is
        // going, so the pull engages as the angle arrives instead of having to
        // be anticipated a frame early.
        val resistance = detentResistance(proposed)
        degrees = wrapDegrees(degrees + deltaDegrees * resistance)
        return degrees
    }

    /**
     * How strongly to resist motion at [angle].
     *
     * Three zones, which is what makes the difference between "stiff" and
     * "stiff only at the angles":
     *
     *  - within [detentCore] of a right angle: full [detentDamping]
     *  - fading out to [detentRange]: released quickly
     *  - beyond that: completely free, 1:1 with the fingers
     *
     * The earlier version smoothstepped across the *whole* range, so the
     * stiffness was still around 0.57 at six degrees out - far too wide, and
     * that is what made rotation feel heavy everywhere. Keeping the stiff part
     * inside a few degrees and dropping it fast means the angle snaps and then
     * gets out of the way.
     */
    private fun detentResistance(angle: Float): Float {
        val offset = abs(wrapToQuarter(angle))
        if (offset >= detentRange) return 1f
        if (offset <= detentCore) return detentDamping

        // 0 at the edge of the core, 1 at the edge of the range.
        val t = (offset - detentCore) / (detentRange - detentCore)
        // Smoothstep, so the hand-off at both boundaries has no visible corner.
        val eased = t * t * (3f - 2f * t)
        return detentDamping + (1f - detentDamping) * eased
    }

    /** Re-seeds the smoother, e.g. when a gesture starts on a rotated object. */
    fun reset(to: Float = 0f) {
        degrees = to
    }

    companion object {
        /**
         * Applied fraction at the centre of a detent.
         *
         * Very low, because the core is now narrow enough that the hold comes
         * from the angle catching rather than from the object refusing to
         * move. Anything higher and even one degree of resistance is felt
         * across the whole turn.
         */
        const val DETENT_DAMPING = 0.08f

        /**
         * Per-frame tremor ignored everywhere.
         *
         * A fingertip resting on glass wobbles by a fraction of a degree.
         * Only here to stop that noise accumulating - far too small to make
         * free rotation feel stepped.
         */
        const val DEAD_ZONE_DEGREES = 0.15f

        /**
         * Half-width of a detent.
         *
         * Five degrees. Anything beyond this is entirely free, so the object
         * is never more than a few degrees from being light again.
         */
        const val DETENT_RANGE = 5f

        /**
         * Half-width of the fully stiff part.
         *
         * One and a half degrees either side of the right angle. Deliberately
         * this tight: the hold should be a precise snap that you can feel land,
         * not a zone of resistance to drag through. Past a degree and a half the
         * angle has already caught, so keeping the resistance any wider only
         * makes the turn in between heavier without making the angle any
         * easier to hit.
         */
        const val DETENT_CORE = 1.5f
    }
}

/** Fold an angle into (-180, 180] so incremental rotation never unwinds. */
internal fun wrapDegrees(deg: Float): Float = ((deg + 180f) % 360f + 360f) % 360f - 180f

/**
 * Folds an angle into (-45, 45]: its distance to the nearest quarter turn.
 *
 * Reducing modulo 90 is what makes this a distance to *any* of the four right
 * angles rather than to a chosen pair, so the detents exist at 0, 90, 180 and
 * 270 alike.
 */
private fun wrapToQuarter(deg: Float): Float {
    var d = wrapDegrees(deg) % 90f
    if (d > 45f) d -= 90f
    if (d < -45f) d += 90f
    return d
}