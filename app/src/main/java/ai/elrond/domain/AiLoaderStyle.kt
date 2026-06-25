package ai.elrond.domain

/**
 * Which organic loader animates while the AI is thinking (FA-17, from the `organic-loaders` Claude
 * Design handoff). These are the seven loaders the design's selection grid showcases; [number] is
 * the design's own loader number (e.g. 17 = `goo-cluster`, the default = 17c). The animations
 * themselves live in the `ui` layer (`ui/loaders`) so this enum stays Compose-free.
 */
enum class AiLoaderStyle(val number: Int, val label: String) {
    ORBIT(2, "Orbit"),
    SPLIT(5, "Split"),
    COMET(7, "Comet"),
    LAVA(11, "Lava"),
    PINCH(14, "Pinch"),
    RINGS(15, "Rings"),
    CLUSTER(17, "Cluster");

    companion object {
        val DEFAULT = CLUSTER

        fun fromName(name: String?): AiLoaderStyle = entries.firstOrNull { it.name == name } ?: DEFAULT
    }
}
