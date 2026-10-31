package dev.fxloop.agent;

import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.not;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import java.lang.instrument.Instrumentation;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;

/**
 * Plan A of the FXML load-registry: retransform {@code javafx.fxml.FXMLLoader} with exit advice on the
 * instance {@code load()} (all static {@code load(...)} variants delegate to an instance). No members are
 * added, so the transform is retransform-safe. Disable with agent arg {@code fxmlHook=false}.
 */
final class FxmlHook {
    private FxmlHook() {}

    static void install(Instrumentation inst) {
        try {
            new AgentBuilder.Default()
                    .disableClassFormatChanges()
                    .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                    .with(AgentBuilder.InitializationStrategy.NoOp.INSTANCE)
                    .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                    .assureReadEdgeTo(inst, FxmlRegistry.class)
                    .ignore(not(named("javafx.fxml.FXMLLoader")))
                    .type(named("javafx.fxml.FXMLLoader"))
                    .transform((builder, type, loader, module, pd) -> builder.visit(Advice.to(LoadAdvice.class)
                            .on(named("load").and(takesArguments(0)).and(not(isStatic())))))
                    .installOn(inst);
        } catch (Throwable t) {
            AgentLog.warn("FXML hook unavailable (falling back to FxLoopDev.track)", t);
        }
    }

    /** Inlined into FXMLLoader; references only public types visible to the application class loader. */
    static final class LoadAdvice {
        private LoadAdvice() {}

        @Advice.OnMethodExit(suppress = Throwable.class)
        static void exit(@Advice.This javafx.fxml.FXMLLoader self, @Advice.Return Object root) {
            FxmlRegistry.recordFromHook(self.getLocation(), root, self.getController());
        }
    }
}
