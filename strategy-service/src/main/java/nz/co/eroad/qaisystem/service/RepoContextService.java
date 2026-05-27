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
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Manages a local clone of the target test monorepo and extracts per-module
 * context (packages, imports, base classes, naming conventions, sample tests)
 * so code generators can produce output that matches existing project conventions.
 *
 * <p>Also builds an integration/E2E <b>coverage index</b> by scanning test files
 * for integration test markers and extracting component-name references. This index
 * is consumed by {@code E2ECoverageAnalyzer} to determine which impacted components
 * already have integration/E2E test coverage in the test repo.
 *
 * Lifecycle:
 *   1. On startup: try to clone/pull the remote repo (if configured).
 *      If that fails, try the fallback local path (if configured).
 *   2. Scan each configured module directory → populate context cache + coverage index.
 *   3. Expose getContext(testType) for runners to call at generation time.
 *   4. Expose getCoverageIndex() for E2ECoverageAnalyzer.
 *   5. POST /api/strategy/refresh-context re-runs steps 1–2 without restart.
 *
 * If neither the remote repo nor a fallback is available, getContext() returns
 * contextAvailable=false and getCoverageIndex() returns an empty map — both callers
 * fall back transparently to built-in templates.
 *
 * <h2>Credential handling</h2>
 * <ul>
 *   <li>{@code auth.type=token}: token is embedded in the HTTPS URL, so git does not
 *       need a credential helper. {@code credential.helper} is overridden to {@code ""}
 *       to prevent macOS Keychain popups.</li>
 *   <li>{@code auth.type=none} or {@code auth.type=ssh}: the system credential store
 *       (e.g. {@code osxkeychain}) is left intact so git can authenticate using
 *       stored credentials — useful when IntelliJ has already configured GitHub access.</li>
 *   <li>{@code GIT_TERMINAL_PROMPT=0} is always set to prevent interactive terminal prompts
 *       that would hang the service startup.</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RepoContextService {

    private final TargetRepoProperties props;

    /** In-memory cache: "API" | "UI" | "MOBILE" → RepoContext */
    private final Map<String, RepoContext> cache = new ConcurrentHashMap<>();

    /**
     * Merged integration/E2E coverage index across all test modules.
     * componentName → list of integration/E2E test file names covering it.
     * Empty when no repo is configured.
     */
    private volatile Map<String, List<String>> coverageIndex = Map.of();

    private static final int MAX_SAMPLE_FILES = 3;
    private static final int MAX_SAMPLE_SIZE  = 6_000; // chars — skip huge files

    /** PascalCase identifiers that are Java/framework builtins — excluded from coverage index. */
    private static final Set<String> EXCLUDED_NAMES = Set.of(
            "String", "Integer", "Long", "Double", "Boolean", "Object", "List", "Map", "Set",
            "Optional", "Collection", "Iterable", "Iterator", "Array", "Arrays",
            "Override", "SuppressWarnings", "Test", "BeforeEach", "AfterEach",
            "BeforeAll", "AfterAll", "DisplayName", "ExtendWith", "SpringBootTest",
            "MockBean", "Autowired", "Value", "Inject", "Component", "Service",
            "Repository", "Controller", "RestController",
            "Given", "When", "Then", "And", "But", "Feature", "Scenario", "Background",
            "Assertions", "AssertJ", "Mockito", "MockitoExtension", "ArgumentCaptor",
            "RestAssured", "MockMvc", "WebTestClient", "ResponseEntity",
            "HttpStatus", "HttpMethod", "MediaType", "ContentType",
            "UUID", "LocalDate", "LocalDateTime", "Instant", "Duration",
            "IOException", "RuntimeException", "Exception", "Throwable",
            "Before", "After");

    // ─── Lifecycle ─────────────────────────────────────────────────────────────

    @PostConstruct
    public void initialise() {
        if (!props.isConfigured()) {
            log.info("[RepoContextService] No remote repo URL configured. " +
                    "Set aiqa.target-repo.url to enable context-aware generation.");
            // Still try the fallback — useful when the repo is already checked out locally
            if (props.hasFallback()) {
                log.info("[RepoContextService] Attempting fallback local path: '{}'",
                        props.getFallbackLocalPath());
                initFromFallback();
            } else {
                log.info("[RepoContextService] No fallback configured. Using built-in templates.");
            }
            return;
        }

        TargetRepoProperties.Auth auth = props.getAuth();
        log.info("[RepoContextService] Starting with url='{}' branch='{}' authType='{}' tokenLength={}",
                props.getUrl(), props.getBranch(), auth.getType(),
                (auth.getToken() != null ? auth.getToken().length() : 0));
        try {
            cloneOrPull();
            refreshCache(Path.of(props.getLocalPath()));
            log.info("[RepoContextService] Context loaded from remote — API={} UI={} MOBILE={}",
                    contextSummary("API"), contextSummary("UI"), contextSummary("MOBILE"));
        } catch (Exception e) {
            log.error("[RepoContextService] Remote clone/pull failed: {}. Trying fallback...",
                    e.getMessage());
            if (props.hasFallback()) {
                initFromFallback();
            } else {
                log.error("[RepoContextService] No fallback configured. Using built-in templates. " +
                        "Tip: set aiqa.target-repo.fallback-local-path to your local checkout.");
            }
        }
    }

    /**
     * Attempts to load context from the configured fallback local path.
     * Logs clearly at each step so the user knows what happened.
     */
    private void initFromFallback() {
        String fallbackDir = props.getFallbackLocalPath();
        Path fallbackPath  = Path.of(fallbackDir);

        if (!Files.isDirectory(fallbackPath)) {
            log.warn("[RepoContextService] Fallback path '{}' does not exist or is not a directory. " +
                    "Using built-in templates.", fallbackPath);
            return;
        }

        // Optionally pull latest on the fallback if it is a git repo
        if (props.isFallbackPull() && Files.exists(fallbackPath.resolve(".git"))) {
            log.info("[RepoContextService] Attempting git pull on fallback path '{}'", fallbackPath);
            try {
                runGit(fallbackPath, "git", "pull", "origin", props.getBranch());
                log.info("[RepoContextService] Fallback pull succeeded.");
            } catch (Exception pullEx) {
                // Pull failure is non-fatal — we use the cached version
                log.warn("[RepoContextService] Fallback pull failed ({}). " +
                        "Using cached local content as-is.", pullEx.getMessage());
            }
        }

        try {
            refreshCache(fallbackPath);
            log.info("[RepoContextService] Context loaded from fallback '{}' — " +
                            "API={} UI={} MOBILE={}",
                    fallbackPath, contextSummary("API"),
                    contextSummary("UI"), contextSummary("MOBILE"));
        } catch (Exception e) {
            log.error("[RepoContextService] Failed to scan fallback path '{}': {}. " +
                    "Using built-in templates.", fallbackPath, e.getMessage());
        }
    }

    /**
     * Pull the latest commits and rebuild the context cache.
     * Called from {@code POST /api/strategy/refresh-context}.
     */
    public Map<String, Object> refresh() {
        if (!props.isConfigured() && !props.hasFallback()) {
            return Map.of("status", "SKIPPED", "reason", "No remote repo or fallback configured");
        }
        try {
            if (props.isConfigured()) {
                cloneOrPull();
                refreshCache(Path.of(props.getLocalPath()));
            } else {
                initFromFallback();
            }
            return Map.of(
                    "status", "OK",
                    "source", props.isConfigured() ? "remote"  : "fallback",
                    "api",    contextSummary("API"),
                    "ui",     contextSummary("UI"),
                    "mobile", contextSummary("MOBILE"));
        } catch (Exception e) {
            log.error("[RepoContextService] Refresh failed, trying fallback: {}", e.getMessage());
            if (props.hasFallback()) {
                try {
                    initFromFallback();
                    return Map.of("status", "OK_FALLBACK",
                            "source", "fallback",
                            "api",    contextSummary("API"),
                            "ui",     contextSummary("UI"),
                            "mobile", contextSummary("MOBILE"));
                } catch (Exception fe) {
                    return Map.of("status", "ERROR",
                            "message", "Remote failed: " + e.getMessage()
                                    + ". Fallback failed: " + fe.getMessage());
                }
            }
            return Map.of("status", "ERROR", "message", e.getMessage());
        }
    }

    /** Returns context for the given test type (API / UI / MOBILE). */
    public RepoContext getContext(String testType) {
        return cache.getOrDefault(
                testType.toUpperCase(),
                RepoContext.builder().testType(testType).contextAvailable(false).build());
    }

    /**
     * Returns the merged integration/E2E coverage index across ALL test modules.
     * Key   = component name (e.g. "PaymentController", "OrderService")
     * Value = list of integration/E2E test file names that reference this component.
     *
     * <p>Returns an empty map when no repo is configured or the clone failed.
     * {@code E2ECoverageAnalyzer} uses this to determine actual E2E test coverage.
     */
    public Map<String, List<String>> getCoverageIndex() {
        return coverageIndex;
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

        // ─── Credential handling ───────────────────────────────────────────────
        //
        // GIT_TERMINAL_PROMPT=0 is ALWAYS set — prevents interactive prompts that
        // would hang the service startup (git would wait forever for keyboard input).
        pb.environment().put("GIT_TERMINAL_PROMPT", "0");

        // Determine whether we are using token-in-URL authentication.
        // When we are, the token is already embedded in the clone URL so the
        // credential helper is not needed — we can safely disable it to prevent
        // the macOS Keychain dialog from appearing.
        //
        // When we are NOT using a token (auth.type = "none" or "ssh", or token is blank),
        // we leave the credential helper INTACT so git can authenticate via:
        //   - osxkeychain (macOS) — where IntelliJ has stored the GitHub token
        //   - libsecret (Linux)
        //   - git credential store
        //   - SSH agent (for auth.type=ssh)
        boolean usingEmbeddedToken = "token".equalsIgnoreCase(props.getAuth().getType())
                && props.getAuth().getToken() != null
                && !props.getAuth().getToken().isBlank();

        if (cmd.length > 0 && cmd[0].equals("git")) {
            java.util.List<String> enriched = new java.util.ArrayList<>();
            enriched.add("git");
            if (usingEmbeddedToken) {
                // Token is in the URL — disable credential helper to silence Keychain popups
                enriched.add("-c");
                enriched.add("credential.helper=");
            }
            // If NOT using embedded token, the credential helper runs normally — git will
            // use osxkeychain (or whatever is configured) to authenticate.
            for (int i = 1; i < cmd.length; i++) enriched.add(cmd[i]);
            pb.command(enriched);
        }

        if (usingEmbeddedToken) {
            // Prevent any askpass GUI — token in URL means no prompt needed
            pb.environment().put("GIT_ASKPASS", "echo");
        }
        // When NOT using embedded token, GIT_ASKPASS is intentionally not set so that
        // the credential helper can prompt via its own mechanism (keychain, etc.).

        Process proc = pb.start();
        String out = new String(proc.getInputStream().readAllBytes());
        int exit = proc.waitFor();
        if (exit != 0) {
            throw new IOException("git command failed (exit " + exit + "): " + out.trim());
        }
        log.debug("[RepoContextService] git: {}", out.trim());
    }

    // ─── Context scanning ──────────────────────────────────────────────────────

    /**
     * Scans the test modules under {@code basePath} and rebuilds the in-memory
     * context cache and coverage index.
     *
     * @param basePath the root of the test repository to scan — either the primary
     *                 clone at {@code localPath} or the fallback local directory
     */
    private void refreshCache(Path basePath) {
        // Load agent instructions once — they live at repo root, not per-module
        Map<String, Map<String, String>> agentInstructionsByType =
                loadAgentInstructions(basePath);

        // Build coverage index per module, then merge
        Map<String, List<String>> mergedIndex = new HashMap<>();

        for (String type : List.of("API", "UI", "MOBILE")) {
            Map<String, List<String>> moduleIndex = buildCoverageIndex(
                    basePath.resolve(props.modulePathFor(type)));

            // Merge into merged index
            moduleIndex.forEach((comp, files) ->
                    mergedIndex.computeIfAbsent(comp, k -> new ArrayList<>()).addAll(files));

            cache.put(type, scanModule(type, props.modulePathFor(type),
                    basePath,
                    agentInstructionsByType.getOrDefault(type, Map.of()),
                    moduleIndex));
        }

        coverageIndex = Collections.unmodifiableMap(mergedIndex);
        log.info("[RepoContextService] Coverage index built — {} components tracked across all modules",
                coverageIndex.size());
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
                                   Path basePath,
                                   Map<String, String> agentInstructions,
                                   Map<String, List<String>> moduleCoverageIndex) {
        Path modulePath = basePath.resolve(moduleRelPath);

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
                        .coverageIndex(Map.of())
                        .build();
            }
            log.warn("[RepoContextService] Module '{}' not found at '{}' — " +
                    "skipping context for {}", testType, modulePath, testType);
            return RepoContext.builder().testType(testType).contextAvailable(false)
                    .coverageIndex(Map.of()).build();
        }

        List<Path> testFiles = findTestFiles(modulePath);

        String       basePackage   = extractBasePackage(testFiles);
        List<String> commonImports = extractCommonImports(testFiles);
        String       baseClass     = detectBaseClass(testFiles);
        String       naming        = detectNamingConvention(testFiles);
        Map<String, String> samples = loadSamples(testFiles);

        log.info("[RepoContextService] {} module: {} test files, package='{}', baseClass='{}', " +
                        "agentFiles={}, coverageIndex={}",
                testType, testFiles.size(), basePackage, baseClass,
                agentInstructions.size(), moduleCoverageIndex.size());

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
                .coverageIndex(moduleCoverageIndex)
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

    // ─── Coverage index building ───────────────────────────────────────────────

    /**
     * Scans {@code modulePath} for integration/E2E test files and builds an inverted index:
     * component name → list of test files that reference it.
     *
     * <p><b>Integration test detection criteria (any one match):</b>
     * <ul>
     *   <li>Gherkin feature file ({@code .feature})</li>
     *   <li>File name contains {@code IT}, {@code IntTest}, {@code IntegrationTest},
     *       {@code E2E}, {@code Acceptance}</li>
     *   <li>File content contains {@code @SpringBootTest}, {@code @IntegrationTest},
     *       {@code RestAssured}, {@code MockMvc}, or {@code WebTestClient}</li>
     * </ul>
     *
     * <p>Component names are extracted by scanning for PascalCase identifiers in each
     * file. Common Java/framework names are excluded (see {@link #EXCLUDED_NAMES}).
     */
    private Map<String, List<String>> buildCoverageIndex(Path modulePath) {
        if (!Files.isDirectory(modulePath)) return Map.of();

        Map<String, List<String>> index = new HashMap<>();

        try (Stream<Path> walk = Files.walk(modulePath)) {
            walk.filter(Files::isRegularFile)
                .filter(this::isIntegrationOrE2eFile)
                .forEach(file -> {
                    Set<String> refs = extractClassReferences(file);
                    String fileName  = file.getFileName().toString();
                    refs.forEach(ref ->
                            index.computeIfAbsent(ref, k -> new ArrayList<>()).add(fileName));
                });
        } catch (IOException e) {
            log.error("[RepoContextService] Error building coverage index for {}: {}",
                    modulePath, e.getMessage());
        }

        log.debug("[RepoContextService] Coverage index for '{}': {} component entries",
                modulePath.getFileName(), index.size());
        return index;
    }

    /**
     * Returns {@code true} if the file is an integration or E2E test, not a unit test.
     *
     * <p>This distinction matters because unit tests (JUnit + Mockito, no Spring context)
     * do NOT validate integration behaviour. Only tests that boot the full Spring context,
     * use an actual HTTP client (RestAssured / WebTestClient / MockMvc with @SpringBootTest),
     * or describe flows in Gherkin are considered integration/E2E.
     */
    private boolean isIntegrationOrE2eFile(Path file) {
        String name = file.getFileName().toString();

        // Gherkin feature files are always integration/E2E
        if (name.endsWith(".feature")) return true;

        boolean isTestFile = name.endsWith(".java") || name.endsWith(".kt");
        if (!isTestFile) return false;

        // Filename-based heuristics (strongest signal)
        if (name.matches(".*(IT|IntTest|IntegrationTest|E2ETest|AcceptanceTest)\\..*")) return true;

        // Content-based detection (fallback for projects without naming conventions)
        try {
            String content = Files.readString(file);
            return content.contains("@SpringBootTest")
                || content.contains("@IntegrationTest")
                || content.contains("RestAssured")
                || content.contains("MockMvc")
                || content.contains("WebTestClient")
                || content.contains("TestRestTemplate");
        } catch (IOException e) {
            return false;
        }
    }

    private static final Pattern PASCAL_CASE = Pattern.compile("\\b([A-Z][a-zA-Z0-9]{2,})\\b");

    /**
     * Extracts PascalCase class-name references from a test file's content.
     * Short names (≤ 2 chars) and those in {@link #EXCLUDED_NAMES} are dropped.
     */
    private Set<String> extractClassReferences(Path file) {
        try {
            String content = Files.readString(file);
            Set<String> refs = new HashSet<>();
            Matcher m = PASCAL_CASE.matcher(content);
            while (m.find()) {
                String name = m.group(1);
                if (!EXCLUDED_NAMES.contains(name)) refs.add(name);
            }
            return refs;
        } catch (IOException e) {
            log.debug("[RepoContextService] Could not read file for coverage indexing: {}", file);
            return Set.of();
        }
    }
}

