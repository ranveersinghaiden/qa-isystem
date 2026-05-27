package nz.co.eroad.qaisystem.service;

import nz.co.eroad.qaisystem.config.TargetRepoProperties;
import nz.co.eroad.qaisystem.execution.RepoContext;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.HashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages a local clone of the target test monorepo and extracts per-module
 * context (packages, imports, base classes, naming conventions, sample tests)
 * so code generators can produce output that matches existing project conventions.
 *
 * Lifecycle:
 *   1. On startup: clone the repo if it doesn't exist; pull latest if it does.
 *   2. Scan each configured module directory → populate context cache.
 *   3. Expose getContext(testType) for runners to call at generation time.
 *   4. POST /api/strategy/refresh-context re-runs steps 1–2 without restart.
 *
 * If the repo URL is not configured, or if the clone/pull fails, every
 * getContext() call returns a context with {@code contextAvailable = false}
 * and runners fall back to built-in templates transparently.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepoContextService {

    private final TargetRepoProperties props;

    /** In-memory cache: "API" | "UI" | "MOBILE" → RepoContext */
    private final Map<String, RepoContext> cache = new ConcurrentHashMap<>();

    private static final int MAX_SAMPLE_FILES = 3;
    private static final int MAX_SAMPLE_SIZE  = 6_000; // chars — skip huge files

    // ─── Lifecycle ─────────────────────────────────────────────────────────────

    @PostConstruct
    public void initialise() {
        if (!props.isConfigured()) {
            log.info("[RepoContextService] No target repo URL configured — " +
                    "codegen will use built-in templates. " +
                    "Set aiqa.target-repo.url to enable context-aware generation.");
            return;
        }
        TargetRepoProperties.Auth auth = props.getAuth();
        log.info("[RepoContextService] Starting with url='{}' branch='{}' authType='{}' tokenLength={}",
                props.getUrl(), props.getBranch(), auth.getType(),
                (auth.getToken() != null ? auth.getToken().length() : 0));
        try {
            cloneOrPull();
            refreshCache();
            log.info("[RepoContextService] Context loaded — API={} UI={} MOBILE={}",
                    contextSummary("API"), contextSummary("UI"), contextSummary("MOBILE"));
        } catch (Exception e) {
            log.error("[RepoContextService] Initialisation failed ({}). " +
                    "Falling back to built-in templates.", e.getMessage(), e);
        }
    }

    /**
     * Pull the latest commits and rebuild the context cache.
     * Called from {@code POST /api/strategy/refresh-context}.
     */
    public Map<String, Object> refresh() {
        if (!props.isConfigured()) {
            return Map.of("status", "SKIPPED", "reason", "No target repo configured");
        }
        try {
            cloneOrPull();
            refreshCache();
            return Map.of(
                    "status", "OK",
                    "api",    contextSummary("API"),
                    "ui",     contextSummary("UI"),
                    "mobile", contextSummary("MOBILE"));
        } catch (Exception e) {
            log.error("[RepoContextService] Refresh failed: {}", e.getMessage(), e);
            return Map.of("status", "ERROR", "message", e.getMessage());
        }
    }

    /** Returns context for the given test type (API / UI / MOBILE). */
    public RepoContext getContext(String testType) {
        return cache.getOrDefault(
                testType.toUpperCase(),
                RepoContext.builder().testType(testType).contextAvailable(false).build());
    }

    // ─── Git operations ────────────────────────────────────────────────────────

    private void cloneOrPull() throws IOException, InterruptedException {
        Path local = Path.of(props.getLocalPath());
        if (Files.exists(local.resolve(".git"))) {
            log.info("[RepoContextService] Pulling latest from '{}'", props.getBranch());
            runGit(local, "git", "pull", "origin", props.getBranch());
        } else {
            log.info("[RepoContextService] Cloning '{}' (branch={}) → {}",
                    props.getUrl(), props.getBranch(), local);
            Files.createDirectories(local.getParent());
            runGit(local.getParent(),
                    "git", "clone",
                    buildCloneUrl(),
                    "--branch", props.getBranch(),
                    "--depth", "1",
                    local.getFileName().toString());
        }
    }

    /**
     * Builds the effective clone URL.
     * For token auth, injects the token into the HTTPS URL:
     *   https://github.com/org/repo → https://{token}@github.com/org/repo
     */
    private String buildCloneUrl() {
        TargetRepoProperties.Auth auth = props.getAuth();
        if ("token".equalsIgnoreCase(auth.getType())
                && auth.getToken() != null && !auth.getToken().isBlank()) {
            String token = auth.getToken();
            String user  = auth.getUsername().isBlank() ? token : auth.getUsername();
            return props.getUrl().replace("https://",
                    "https://" + user + ":" + token + "@");
        }
        return props.getUrl(); // ssh or public https
    }

    private void runGit(Path workDir, String... cmd) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .directory(workDir.toFile())
                .redirectErrorStream(true);

        // Disable ALL interactive credential prompts so git fails fast instead of hanging.
        // GIT_TERMINAL_PROMPT=0 → no terminal prompt
        // GIT_ASKPASS=echo      → any askpass program returns empty (prevents macOS Keychain dialog)
        // credential.helper=''  → disable the system credential store (e.g. osxkeychain)
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");
        pb.environment().put("GIT_ASKPASS", "echo");

        // Prepend -c credential.helper='' after 'git' to override osxkeychain etc.
        // Only inject for git commands (not raw shell commands).
        if (cmd.length > 0 && cmd[0].equals("git")) {
            java.util.List<String> enriched = new java.util.ArrayList<>();
            enriched.add("git");
            enriched.add("-c");
            enriched.add("credential.helper=");
            for (int i = 1; i < cmd.length; i++) enriched.add(cmd[i]);
            pb.command(enriched);
        }

        Process proc = pb.start();
        String out = new String(proc.getInputStream().readAllBytes());
        int exit = proc.waitFor();
        if (exit != 0) {
            throw new IOException("git command failed (exit " + exit + "): " + out.trim());
        }
        log.debug("[RepoContextService] git: {}", out.trim());
    }

    // ─── Context scanning ──────────────────────────────────────────────────────

    private void refreshCache() {
        // Load agent instructions once — they live at repo root, not per-module
        Map<String, Map<String, String>> agentInstructionsByType =
                loadAgentInstructions(Path.of(props.getLocalPath()));

        for (String type : List.of("API", "UI", "MOBILE")) {
            cache.put(type, scanModule(type, props.modulePathFor(type),
                    agentInstructionsByType.getOrDefault(type, Map.of())));
        }
    }

    /**
     * Scans {@code .github/agents/} at the repo root and distributes files to
     * the relevant test type(s).
     *
     * <p>Matching rules (case-insensitive filename):
     * <ul>
     *   <li>Contains "api"    → API</li>
     *   <li>Contains "ui" or "web" or "selenium" or "playwright" → UI</li>
     *   <li>Contains "mobile" or "appium" or "android" or "ios" → MOBILE</li>
     *   <li>None of the above → added to <em>all three</em> types as a shared convention</li>
     * </ul>
     */
    private Map<String, Map<String, String>> loadAgentInstructions(Path repoRoot) {
        Map<String, Map<String, String>> result = new HashMap<>();
        result.put("API",    new LinkedHashMap<>());
        result.put("UI",     new LinkedHashMap<>());
        result.put("MOBILE", new LinkedHashMap<>());

        Path agentsDir = repoRoot.resolve(".github").resolve("agents");
        if (!Files.isDirectory(agentsDir)) {
            log.debug("[RepoContextService] No .github/agents/ directory found — " +
                    "skipping agent instruction loading.");
            return result;
        }

        try (Stream<Path> files = Files.list(agentsDir)) {
            files.filter(Files::isRegularFile)
                 .filter(p -> {
                     String n = p.getFileName().toString().toLowerCase();
                     return n.endsWith(".md") || n.endsWith(".txt")
                             || n.endsWith(".instructions.md");
                 })
                 .sorted()
                 .forEach(p -> {
                     try {
                         String name    = p.getFileName().toString();
                         String nameLow = name.toLowerCase();
                         String content = Files.readString(p);

                         boolean isApi    = nameLow.contains("api");
                         boolean isUi     = nameLow.contains("ui")     || nameLow.contains("web")
                                         || nameLow.contains("selenium") || nameLow.contains("playwright");
                         boolean isMobile = nameLow.contains("mobile") || nameLow.contains("appium")
                                         || nameLow.contains("android") || nameLow.contains("ios");
                         boolean isShared = !isApi && !isUi && !isMobile;

                         if (isApi    || isShared) result.get("API").put(name, content);
                         if (isUi     || isShared) result.get("UI").put(name, content);
                         if (isMobile || isShared) result.get("MOBILE").put(name, content);

                         log.info("[RepoContextService] Loaded agent instructions: {} → {}",
                                 name, isShared ? "ALL" : (isApi ? "API " : "") +
                                         (isUi ? "UI " : "") + (isMobile ? "MOBILE" : ""));
                     } catch (IOException e) {
                         log.warn("[RepoContextService] Could not read agent file {}: {}",
                                 p, e.getMessage());
                     }
                 });
        } catch (IOException e) {
            log.error("[RepoContextService] Error scanning .github/agents/: {}", e.getMessage());
        }

        long total = result.values().stream().mapToLong(m -> m.size()).sum();
        if (total > 0) {
            log.info("[RepoContextService] Agent instructions loaded — API={} UI={} MOBILE={}",
                    result.get("API").size(), result.get("UI").size(), result.get("MOBILE").size());
        }
        return result;
    }

    private RepoContext scanModule(String testType, String moduleRelPath,
                                   Map<String, String> agentInstructions) {
        Path modulePath = Path.of(props.getLocalPath(), moduleRelPath);

        // If we have agent instructions, the context is usable even without test source files
        boolean hasAgentFiles = !agentInstructions.isEmpty();

        if (!Files.isDirectory(modulePath)) {
            if (hasAgentFiles) {
                log.info("[RepoContextService] Module '{}' dir not found but agent instructions " +
                        "are available — using agent-only context for {}", modulePath, testType);
                return RepoContext.builder()
                        .testType(testType)
                        .contextAvailable(true)
                        .agentInstructions(agentInstructions)
                        .build();
            }
            log.warn("[RepoContextService] Module '{}' not found at '{}' — " +
                    "skipping context for {}", testType, modulePath, testType);
            return RepoContext.builder().testType(testType).contextAvailable(false).build();
        }

        List<Path> testFiles = findTestFiles(modulePath);

        String       basePackage   = extractBasePackage(testFiles);
        List<String> commonImports = extractCommonImports(testFiles);
        String       baseClass     = detectBaseClass(testFiles);
        String       naming        = detectNamingConvention(testFiles);
        Map<String, String> samples = loadSamples(testFiles);

        log.info("[RepoContextService] {} module: {} test files, package='{}', baseClass='{}', " +
                        "agentFiles={}",
                testType, testFiles.size(), basePackage, baseClass, agentInstructions.size());

        return RepoContext.builder()
                .testType(testType)
                .contextAvailable(true)
                .agentInstructions(agentInstructions)
                .basePackage(basePackage)
                .repoModulePath(modulePath.toString())
                .existingTestFileNames(testFiles.stream()
                        .map(p -> p.getFileName().toString())
                        .collect(Collectors.toList()))
                .commonImports(commonImports)
                .baseTestClass(baseClass)
                .testNamingConvention(naming)
                .sampleTests(samples)
                .build();
    }

    // ─── File scanning helpers ─────────────────────────────────────────────────

    private List<Path> findTestFiles(Path root) {
        try (Stream<Path> walk = Files.walk(root)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return (name.endsWith(".java") || name.endsWith(".kt"))
                                && (name.contains("Test") || name.contains("Spec")
                                    || name.contains("IT"));
                    })
                    // Sort by file size ascending so small representative files come first
                    .sorted(Comparator.comparingLong(p -> {
                        try { return Files.size(p); } catch (IOException e) { return 0L; }
                    }))
                    .collect(Collectors.toList());
        } catch (IOException e) {
            log.error("[RepoContextService] Error scanning {}: {}", root, e.getMessage());
            return List.of();
        }
    }

    /** Reads the first `package` statement found across all test files. */
    private String extractBasePackage(List<Path> files) {
        return files.stream()
                .flatMap(f -> safeLines(f).filter(l -> l.trim().startsWith("package ")))
                .findFirst()
                .map(l -> l.replace("package", "").replace(";", "").trim())
                .orElse("nz.co.eroad.qaisystem.generated.tests");
    }

    /**
     * Collects imports that appear in ≥ 50 % of the scanned files.
     * These represent the project's standard testing libraries.
     */
    private List<String> extractCommonImports(List<Path> files) {
        Map<String, Long> counts = new LinkedHashMap<>();
        files.forEach(f ->
                safeLines(f)
                        .filter(l -> l.trim().startsWith("import "))
                        .map(l -> l.replace("import", "").replace(";", "").trim())
                        .distinct()
                        .forEach(imp -> counts.merge(imp, 1L, Long::sum)));

        long threshold = Math.max(1, files.size() / 2);
        return counts.entrySet().stream()
                .filter(e -> e.getValue() >= threshold)
                .map(Map.Entry::getKey)
                .sorted()
                .collect(Collectors.toList());
    }

    /**
     * Detects a common base test class.
     * Looks for `class XxxTest extends SomethingBase` in the first few files.
     */
    private String detectBaseClass(List<Path> files) {
        return files.stream()
                .limit(10)
                .flatMap(f -> safeLines(f)
                        .filter(l -> l.contains("class ") && l.contains(" extends ")))
                .findFirst()
                .map(l -> {
                    int idx = l.indexOf(" extends ") + " extends ".length();
                    return l.substring(idx).split("[{\\s<]")[0].trim();
                })
                .filter(s -> !s.isBlank())
                .orElse(null);
    }

    /** Detects whether files follow suffix (*Test) or prefix (Test*) naming. */
    private String detectNamingConvention(List<Path> files) {
        long prefix = files.stream()
                .filter(p -> p.getFileName().toString().startsWith("Test"))
                .count();
        return (prefix > files.size() / 2) ? "Test*.java" : "*Test.java";
    }

    /**
     * Loads up to {@value MAX_SAMPLE_FILES} representative test files as source strings.
     * Skips files larger than {@value MAX_SAMPLE_SIZE} characters.
     */
    private Map<String, String> loadSamples(List<Path> files) {
        Map<String, String> samples = new LinkedHashMap<>();
        for (Path f : files) {
            if (samples.size() >= MAX_SAMPLE_FILES) break;
            try {
                String content = Files.readString(f);
                if (content.length() <= MAX_SAMPLE_SIZE) {
                    samples.put(f.getFileName().toString(), content);
                }
            } catch (IOException e) {
                log.debug("[RepoContextService] Could not read sample {}: {}", f, e.getMessage());
            }
        }
        return samples;
    }

    private Stream<String> safeLines(Path f) {
        try { return Files.lines(f); } catch (IOException e) { return Stream.empty(); }
    }

    private String contextSummary(String type) {
        RepoContext ctx = cache.get(type);
        if (ctx == null || !ctx.isContextAvailable()) return "no-context";
        int agentFiles  = ctx.getAgentInstructions() != null ? ctx.getAgentInstructions().size() : 0;
        int testFiles   = ctx.getExistingTestFileNames() != null ? ctx.getExistingTestFileNames().size() : 0;
        return testFiles + " tests, pkg=" + ctx.getBasePackage() + ", agentFiles=" + agentFiles;
    }
}

