package io.github.baokhang83.mnemo.warmup.core;

import java.nio.file.Path;
import java.time.Duration;

/** Trains an archive for a prepared application using an {@link ExitMode}. */
public final class AppTrainer {

    private final TrainingRunner runner = new TrainingRunner();

    /**
     * @param app      the output of {@link JarExtractor#prepare}
     * @param jdk      the target JDK, which both runs the training and must run the app later
     * @param strategy AppCDS or AOT, already resolved for {@code jdk}
     * @param mode     how the run ends
     * @param archive  where to write the archive
     * @param timeout  how long {@code mode} may take to reach its ready state
     * @throws IllegalArgumentException if {@code mode} cannot drive this kind of application
     * @throws TrainingException        if training fails
     */
    public TrainingRunner.Result train(
            JarExtractor.ExtractedApp app,
            JdkInfo jdk,
            CacheStrategy strategy,
            ExitMode mode,
            Path archive,
            Duration timeout) {
        if (!mode.supports(app.kind())) {
            throw new IllegalArgumentException("Exit mode '" + mode.name() + "' does not support "
                    + app.kind() + " applications (" + app.runnableJar() + ")");
        }
        TrainingSpec.Builder spec = TrainingSpec.builder(jdk.javaExecutable(), strategy, archive)
                .appArgs("-jar", app.runnableJar().toString())
                .ready(mode.readyCondition())
                .timeout(timeout);
        mode.jvmArgs().forEach(spec::jvmArg);
        return runner.run(spec.build());
    }
}
