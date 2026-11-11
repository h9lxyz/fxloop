package org.lab59.fxloop.gradle

import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

/** The `fxloop { ... }` DSL block. */
abstract class FxloopExtension {
    /** Main class (optionally `module/fqcn`); overrides all auto-detection. */
    abstract val mainClass: Property<String>

    /** Extra JVM arguments for the launched application. */
    abstract val jvmArgs: ListProperty<String>

    /** Extra program arguments for the launched application. */
    abstract val appArgs: ListProperty<String>

    /** `auto` (default), `classpath` or `modulepath`. */
    abstract val launchMode: Property<String>
}
