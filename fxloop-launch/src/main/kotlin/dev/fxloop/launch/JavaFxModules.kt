package dev.fxloop.launch

/** Derives JavaFX module names from `org.openjfx` artifact ids. */
object JavaFxModules {

    const val GROUP_ID = "org.openjfx"

    private const val PREFIX = "javafx-"

    /** `javafx-controls` to `javafx.controls`; `null` when not a JavaFX artifact id. */
    @JvmStatic
    fun moduleName(artifactId: String?): String? =
        if (artifactId == null || !artifactId.startsWith(PREFIX) || artifactId.length <= PREFIX.length) null
        else artifactId.replace('-', '.')

    /** Distinct module names, in first-seen order; classifier duplicates (same artifactId) collapse. */
    @JvmStatic
    fun moduleNames(artifactIds: List<String>): List<String> =
        artifactIds.mapNotNull(::moduleName).distinct()
}
