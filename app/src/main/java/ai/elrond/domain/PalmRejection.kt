package ai.elrond.domain

/**
 * Pure palm-rejection decision, factored out of [InkCanvas] so it can be unit-tested without
 * a real `MotionEvent`. Tool type is passed as a boolean to keep this Android-free.
 */
object PalmRejection {

    /**
     * Whether a touch should be ignored for inking. While stylus-only mode is on, finger
     * touches (a resting palm/hand) are rejected so they never draw; stylus and hardware
     * eraser touches are always allowed.
     */
    fun shouldReject(isFinger: Boolean, stylusOnly: Boolean): Boolean = isFinger && stylusOnly
}
