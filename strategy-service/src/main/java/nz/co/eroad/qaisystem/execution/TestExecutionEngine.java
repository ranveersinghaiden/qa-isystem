package nz.co.eroad.qaisystem.execution;

import nz.co.eroad.qaisystem.model.TestResult;
import nz.co.eroad.qaisystem.model.TestScript;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.engine.JupiterTestEngine;
import org.junit.platform.engine.discovery.DiscoverySelectors;
import org.junit.platform.launcher.Launcher;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.core.LauncherConfig;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;
import org.junit.platform.launcher.listeners.SummaryGeneratingListener;
import org.junit.platform.launcher.listeners.TestExecutionSummary;
import org.springframework.stereotype.Service;

import javax.tools.*;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Compiles and executes generated test code using the Java Compiler API
 * and the JUnit Platform Launcher.
 *
 * <p>Flow per attempt:
 * <ol>
 *   <li>Write source to an isolated temp dir preserving package structure.</li>
 *   <li>Compile with {@code javax.tools.JavaCompiler} using the running JVM classpath
 *       (which includes RestAssured, AssertJ, JUnit 5, Cucumber, Selenium, Appium).</li>
 *   <li>If compilation fails, return a FAIL result with diagnostics so
 *       {@link StabilizationLoop} can apply a targeted fix.</li>
 *   <li>Load the compiled class via {@link URLClassLoader} and run it with the
 *       JUnit Platform Launcher, capturing pass/fail/counts from
 *       {@link SummaryGeneratingListener}.</li>
 *   <li>Always clean up the temp directory regardless of outcome.</li>
 * </ol>
 */
@Slf4j
@Service
public class TestExecutionEngine {

    public TestResult execute(TestScript script, int attemptNumber) {
        log.info("[TestExecutionEngine] Compiling and executing '{}' (attempt {})",
                script.getFileName(), attemptNumber);

        long startTime = System.currentTimeMillis();
        Path tempDir   = null;

        try {
            tempDir = Files.createTempDirectory(
                    "qa-gen-" + script.getScriptId() + "-a" + attemptNumber + "-");

            Path sourceFile = writeSourceFile(tempDir, script);

            Path classesDir = tempDir.resolve("classes");
            Files.createDirectories(classesDir);
            CompileResult compile = compileSource(sourceFile, classesDir);

            if (!compile.succeeded()) {
                log.warn("[TestExecutionEngine] Compile FAILED for '{}' (attempt {}):\n{}",
                        script.getFileName(), attemptNumber, compile.errorMessage());
                return TestResult.builder()
                        .resultId(UUID.randomUUID().toString())
                        .scriptId(script.getScriptId())
                        .prId(script.getPrId())
                        .passed(false)
                        .attemptNumber(attemptNumber)
                        .stabilized(false)
                        .executionTimeMs(System.currentTimeMillis() - startTime)
                        .executedAt(LocalDateTime.now())
                        .output("COMPILE_ERROR")
                        .errorMessage(compile.errorMessage())
                        .failureReasons(compile.errors())
                        .finalScriptContent(script.getScriptContent())
                        .build();
            }

            return runTests(classesDir, script, attemptNumber, startTime);

        } catch (Exception e) {
            log.error("[TestExecutionEngine] Error for '{}' (attempt {}): {}",
                    script.getFileName(), attemptNumber, e.getMessage(), e);
            return failResult(script, attemptNumber, startTime,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            deleteTempDir(tempDir);
        }
    }

    // ── Source writing ────────────────────────────────────────────────────────

    private Path writeSourceFile(Path tempDir, TestScript script) throws IOException {
        String pkg = script.getTargetPackage() != null
                ? script.getTargetPackage().replace('.', File.separatorChar)
                : "nz/co/eroad/qaisystem/generated/tests";
        Path srcDir = tempDir.resolve("src").resolve(pkg);
        Files.createDirectories(srcDir);
        Path source = srcDir.resolve(script.getFileName());
        Files.writeString(source, script.getScriptContent(), StandardCharsets.UTF_8);
        log.debug("[TestExecutionEngine] Source written: {}", source);
        return source;
    }

    // ── Compilation ───────────────────────────────────────────────────────────

    private CompileResult compileSource(Path sourceFile, Path outputDir) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return CompileResult.failure(List.of(
                    "JDK compiler not available (JRE-only) — run on a JDK"));
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm =
                     compiler.getStandardFileManager(diagnostics, Locale.ENGLISH, StandardCharsets.UTF_8)) {

            List<String> options = List.of(
                    "-cp",       buildClasspath(),
                    "-d",        outputDir.toString(),
                    "--release", "25"
            );
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null, fm, diagnostics, options, null,
                    fm.getJavaFileObjects(sourceFile.toFile()));
            boolean ok = task.call();

            List<String> errors = diagnostics.getDiagnostics().stream()
                    .filter(d -> d.getKind() == Diagnostic.Kind.ERROR)
                    .map(d -> String.format("%s:%d  %s",
                            d.getSource() != null ? d.getSource().getName() : "<unknown>",
                            d.getLineNumber(), d.getMessage(Locale.ENGLISH)))
                    .collect(Collectors.toList());

            return ok ? CompileResult.success() : CompileResult.failure(errors);

        } catch (IOException e) {
            return CompileResult.failure(List.of("FileManager error: " + e.getMessage()));
        }
    }

    // ── Test execution ────────────────────────────────────────────────────────

    private TestResult runTests(Path classesDir, TestScript script,
                                 int attempt, long startTime) {
        String pkg  = script.getTargetPackage() != null ? script.getTargetPackage() + "." : "";
        String name = script.getFileName().replaceAll("\\.java$", "");
        String className = pkg + name;

        try (URLClassLoader cl = new URLClassLoader(
                new URL[]{classesDir.toUri().toURL()},
                Thread.currentThread().getContextClassLoader())) {

            Class<?> testClass = cl.loadClass(className);

            LauncherDiscoveryRequest request = LauncherDiscoveryRequestBuilder.request()
                    .selectors(DiscoverySelectors.selectClass(testClass))
                    .build();

            SummaryGeneratingListener listener = new SummaryGeneratingListener();

            // Explicitly supply JupiterTestEngine to avoid ServiceLoader problems
            // inside Spring Boot's nested-JAR class loader.
            LauncherConfig config = LauncherConfig.builder()
                    .addTestEngines(new JupiterTestEngine())
                    .build();

            Launcher launcher = LauncherFactory.create(config);
            launcher.execute(request, listener);

            TestExecutionSummary summary  = listener.getSummary();
            long started = summary.getTestsStartedCount();
            long passed  = summary.getTestsSucceededCount();
            long failed  = summary.getTestsFailedCount();
            long skipped = summary.getTestsSkippedCount();

            List<String> failures = summary.getFailures().stream()
                    .map(f -> f.getTestIdentifier().getDisplayName() + ": "
                            + (f.getException() != null ? f.getException().getMessage() : "?"))
                    .collect(Collectors.toList());

            boolean allPassed = started > 0 && failed == 0;
            String  output    = String.format("%d started, %d passed, %d failed, %d skipped",
                    started, passed, failed, skipped);

            log.info("[TestExecutionEngine] '{}' attempt {} -> {} ({})",
                    script.getFileName(), attempt, allPassed ? "PASS" : "FAIL", output);

            return TestResult.builder()
                    .resultId(UUID.randomUUID().toString())
                    .scriptId(script.getScriptId())
                    .prId(script.getPrId())
                    .passed(allPassed)
                    .stabilized(false)
                    .attemptNumber(attempt)
                    .executionTimeMs(System.currentTimeMillis() - startTime)
                    .executedAt(LocalDateTime.now())
                    .output(output)
                    .errorMessage(failures.isEmpty() ? null : String.join("\n", failures))
                    .failureReasons(failures)
                    .finalScriptContent(script.getScriptContent())
                    .build();

        } catch (ClassNotFoundException e) {
            log.error("[TestExecutionEngine] Class '{}' not found: {}", className, e.getMessage());
            return failResult(script, attempt, startTime,
                    "ClassNotFoundException: " + className + " — " + e.getMessage());
        } catch (Exception e) {
            log.error("[TestExecutionEngine] Execution error for '{}': {}",
                    script.getFileName(), e.getMessage(), e);
            return failResult(script, attempt, startTime,
                    e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private TestResult failResult(TestScript s, int attempt, long start, String msg) {
        return TestResult.builder()
                .resultId(UUID.randomUUID().toString())
                .scriptId(s.getScriptId())
                .prId(s.getPrId())
                .passed(false)
                .attemptNumber(attempt)
                .executionTimeMs(System.currentTimeMillis() - start)
                .executedAt(LocalDateTime.now())
                .errorMessage(msg)
                .failureReasons(List.of(msg))
                .finalScriptContent(s.getScriptContent())
                .build();
    }

    private static String buildClasspath() {
        String cp = System.getProperty("java.class.path", "");
        String mp = System.getProperty("jdk.module.path", "");
        return mp.isBlank() ? cp : cp + File.pathSeparator + mp;
    }

    private static void deleteTempDir(Path dir) {
        if (dir == null) return;
        try {
            Files.walk(dir)
                 .sorted(Comparator.reverseOrder())
                 .map(Path::toFile)
                 .forEach(File::delete);
        } catch (Exception ignored) {}
    }

    // ── Inner record ──────────────────────────────────────────────────────────

    private record CompileResult(boolean succeeded, List<String> errors) {
        static CompileResult success()                  { return new CompileResult(true,  List.of()); }
        static CompileResult failure(List<String> errs) { return new CompileResult(false, errs); }
        public String errorMessage()                    { return String.join("\n", errors); }
    }
}
