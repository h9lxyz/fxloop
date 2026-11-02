package dev.fxloop.launch

/** Launch-mode rule and `module-info.java` parsing, independent of any build tool. */
object LaunchMode {

    const val CLASSPATH = "classpath"
    const val MODULEPATH = "modulepath"

    /**
     * Resolves a requested mode (`auto`, `classpath`, `modulepath`; `null` means `auto`, case-insensitive) to
     * `classpath` or `modulepath`. `auto` yields `modulepath` iff [modular] (a `module-info.java` exists).
     *
     * @throws IllegalArgumentException for any other value, with the message callers surface unchanged.
     */
    @JvmStatic
    fun resolve(requested: String?, modular: Boolean): String =
        when (val m = (requested ?: "auto").trim().lowercase()) {
            "auto" -> if (modular) MODULEPATH else CLASSPATH
            CLASSPATH, MODULEPATH -> m
            else -> throw IllegalArgumentException(
                "Invalid fxloop.launchMode '$requested' (expected auto|classpath|modulepath)",
            )
        }

    private val MODULE_NAME = Regex("""(?:^|\s)module\s+([\w.]+)\s*\{""", RegexOption.MULTILINE)

    /** The module name declared in `module-info.java` source (comments ignored), or `null`. */
    @JvmStatic
    fun parseModuleName(moduleInfoSource: String): String? {
        val stripped = moduleInfoSource
            .replace(Regex("(?s)/\\*.*?\\*/"), " ")
            .replace(Regex("//[^\\n]*"), " ")
        return MODULE_NAME.find(stripped)?.groupValues?.get(1)
    }
}
