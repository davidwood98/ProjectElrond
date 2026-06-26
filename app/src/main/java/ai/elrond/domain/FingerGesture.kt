package ai.elrond.domain

/**
 * A multi-finger tap gesture detected on the note canvas (FA-19). Distinguished by finger count
 * (2 or 3) and tap count (single or double). Each is user-bound to a [FingerGestureAction].
 *
 * These are intentional gestures, independent of palm rejection: a deliberate 2-/3-finger tap is
 * always detected (it's distinguishable from a resting palm), whether stylus-only mode is on or off.
 */
sealed class FingerGesture {
    object TwoFingerTap : FingerGesture()
    object ThreeFingerTap : FingerGesture()
    object TwoFingerDoubleTap : FingerGesture()
    object ThreeFingerDoubleTap : FingerGesture()
}
