package dev.fxloop.agent

import java.lang.instrument.Instrumentation
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.asm.Advice
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.pool.TypePool
import net.bytebuddy.matcher.ElementMatchers.isStatic
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.not
import net.bytebuddy.matcher.ElementMatchers.takesArguments

/**
 * Plan A of the FXML load-registry: retransform `javafx.fxml.FXMLLoader` with exit advice on the
 * instance `load()` (all static `load(...)` variants delegate to an instance). No members are
 * added, so the transform is retransform-safe. Disable with agent arg `fxmlHook=false`.
 */
internal object FxmlHook {
    fun install(inst: Instrumentation) {
        try {
            // Describe the advice via a type pool: reflecting on LoadAdvice would load FXMLLoader (its parameter type)
            // while FXMLLoader itself is being defined, which fails with a duplicate class definition.
            val locator = ClassFileLocator.ForClassLoader.of(LoadAdvice::class.java.classLoader)
            val advice = Advice.to(TypePool.Default.of(locator).describe(LoadAdvice::class.java.name).resolve(), locator)
            AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .assureReadEdgeTo(inst, FxmlRegistry::class.java)
                .ignore(not(named("javafx.fxml.FXMLLoader")))
                .type(named("javafx.fxml.FXMLLoader"))
                .transform { builder, _, _, _, _ ->
                    builder.visit(
                        advice.on(named<net.bytebuddy.description.method.MethodDescription>("load").and(takesArguments(0)).and(not(isStatic()))),
                    )
                }
                .installOn(inst)
        } catch (t: Throwable) {
            AgentLog.warn("FXML hook unavailable (falling back to FxLoopDev.track)", t)
        }
    }

    /**
     * Inlined into FXMLLoader; references only public types visible to the application class loader. Parameters are
     * nullable so Kotlin emits no `Intrinsics` null-checks into the inlined bytecode.
     */
    object LoadAdvice {
        @JvmStatic
        @Advice.OnMethodExit(suppress = Throwable::class)
        fun exit(@Advice.This self: javafx.fxml.FXMLLoader?, @Advice.Return root: Any?) {
            FxmlRegistry.recordFromHook(self, root)
        }
    }
}
