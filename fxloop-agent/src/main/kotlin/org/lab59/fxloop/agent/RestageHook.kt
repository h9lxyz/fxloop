package org.lab59.fxloop.agent

import java.lang.instrument.Instrumentation
import net.bytebuddy.agent.builder.AgentBuilder
import net.bytebuddy.asm.Advice
import net.bytebuddy.description.method.MethodDescription
import net.bytebuddy.description.type.TypeDescription
import net.bytebuddy.dynamic.ClassFileLocator
import net.bytebuddy.matcher.ElementMatchers.hasSuperType
import net.bytebuddy.matcher.ElementMatchers.isAbstract
import net.bytebuddy.matcher.ElementMatchers.named
import net.bytebuddy.matcher.ElementMatchers.nameStartsWith
import net.bytebuddy.matcher.ElementMatchers.not
import net.bytebuddy.matcher.ElementMatchers.takesArguments
import net.bytebuddy.pool.TypePool

/**
 * Opt-in `restage` support (PRD 07 §3): enter advice on `start(Stage)` of `Application` subtypes records the
 * application instance and its primary stage, so [MountRegistry] can re-run `start` after a hotswap. Advice
 * failures are suppressed; installation failures are only logged.
 */
internal object RestageHook {
    @Volatile
    private var app: Any? = null

    @Volatile
    private var stage: Any? = null

    /** The captured `(Application, Stage)` pair, or `null` when `start(Stage)` was not observed. */
    fun captured(): Pair<Any, Any>? {
        val a = app ?: return null
        val s = stage ?: return null
        return a to s
    }

    /** Called from the advice; must never throw. */
    @JvmStatic
    fun record(application: Any?, primary: Any?) {
        if (application == null || primary == null) return
        if (app == null) {
            app = application
            stage = primary
        }
    }

    fun install(inst: Instrumentation) {
        try {
            val locator = ClassFileLocator.ForClassLoader.of(StartAdvice::class.java.classLoader)
            val advice = Advice.to(TypePool.Default.of(locator).describe(StartAdvice::class.java.name).resolve(), locator)
            AgentBuilder.Default()
                .disableClassFormatChanges()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                .assureReadEdgeTo(inst, RestageHook::class.java)
                .ignore(
                    nameStartsWith<TypeDescription>("java.")
                        .or(nameStartsWith("javax."))
                        .or(nameStartsWith("jdk."))
                        .or(nameStartsWith("sun."))
                        .or(nameStartsWith("javafx."))
                        .or(nameStartsWith("com.sun."))
                        .or(nameStartsWith("org.lab59.fxloop."))
                        .or(nameStartsWith("fxloop.internal.")),
                )
                .type(hasSuperType(named("javafx.application.Application")))
                .transform { builder, _, _, _, _ ->
                    builder.visit(
                        advice.on(
                            named<MethodDescription>("start").and(takesArguments(1)).and(not(isAbstract())),
                        ),
                    )
                }
                .installOn(inst)
        } catch (t: Throwable) {
            AgentLog.warn("restage hook unavailable (restage=true will report an error)", t)
        }
    }

    /** Inlined into `Application.start(Stage)` overrides; parameters are `Object` so no JavaFX type is resolved. */
    object StartAdvice {
        @JvmStatic
        @Advice.OnMethodEnter(suppress = Throwable::class)
        fun enter(@Advice.This self: Any?, @Advice.Argument(0) stage: Any?) {
            RestageHook.record(self, stage)
        }
    }
}
