package dev.fxloop.gradle

/** Derives JavaFX module names from `org.openjfx` artifact ids. */
internal object JavaFxModules {
    const val GROUP_ID = "org.openjfx"

    /** `javafx-controls` to `javafx.controls`; null when not a JavaFX artifact id. */
    fun moduleName(artifactId: String?): String? =
        if (artifactId == null || !artifactId.startsWith("javafx-") || artifactId.length <= "javafx-".length) null
        else artifactId.replace('-', '.')

    /** Distinct module names in first-seen order; classifier duplicates collapse. */
    fun moduleNames(artifactIds: List<String>): List<String> =
        artifactIds.mapNotNull(::moduleName).distinct()
}
