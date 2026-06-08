package nz.co.eroad.qaisystem.execution;

import lombok.Builder;
import lombok.Data;
import nz.co.eroad.qaisystem.context.ProductExpertContext;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Extracted context from the target test monorepo for a specific test type.
 * Passed to each runner so generated code matches the conventions of the
 * existing test suite rather than using generic built-in templates.
 *
 * <p><b>Priority order for context sources:</b>
 * <ol>
 *   <li>{@code agentInstructions} — content from {@code .github/agents/*.md} files.
 *       These are explicit, human-authored convention docs and take highest precedence.</li>
 *   <li>Heuristically scanned conventions (package, base class, common imports, samples)
 *       derived from scanning actual test source files.</li>
 *   <li>Built-in templates — used when {@code contextAvailable == false}.</li>
 * </ol>
 */
@Data
@Builder
public class RepoContext {

    private final String  testType;            // API | UI | MOBILE
    private final boolean contextAvailable;    // false = no repo or module missing

    // ── Agent instruction files (.github/agents/*.md) ──────────────────────────

    /**
     * Content read from {@code .github/agents/} in the target repo.
     * Map of filename → full markdown content.
     * When present these are embedded verbatim into generated files so developers
     * (and AI tools) can see the project's explicit coding conventions at a glance.
     */
    private final Map<String, String> agentInstructions;

    // ── Product expert context (productExpert/{product}/*.md) ─────────────────

    /**
     * Product-specific knowledge files loaded from {@code productExpert/} in the
     * target test repo.  Map of product name → {@link ProductExpertContext}.
     *
     * <p>These files contain domain knowledge (what the product does, business flows,
     * key entities) and testing patterns (how to test this product, known pitfalls).
     * The AI generation pipeline uses them as the primary system prompt context.
     *
     * <p>Empty map when no {@code productExpert/} directory exists in the repo.
     */
    private final Map<String, ProductExpertContext> productExpertSections;

    /**
     * Content of {@code .aiqa/context.md} from the target test repo.
     * This is a repo-level QA context file covering overarching conventions,
     * environment setup, CI notes, etc.
     *
     * <p>{@code null} or blank when the file does not exist.
     */
    private final String repoAiqaContext;

    // ── Heuristically discovered conventions (fallback) ────────────────────────

    /** Root package of existing tests, e.g. "com.example.tests.api" */
    private final String basePackage;

    /** Absolute path to the scanned module directory on disk */
    private final String repoModulePath;

    /** All test file names found in the module (base names only) */
    private final List<String> existingTestFileNames;

    /**
     * Imports that appear in ≥ 50 % of the test files — used to mirror
     * the project's assertion/framework choices in generated code.
     */
    private final List<String> commonImports;

    /**
     * Base/abstract test class that existing tests extend, or null.
     * Generated tests will extend the same class when present.
     */
    private final String baseTestClass;

    /**
     * Detected naming convention: "*Test.java" (suffix) or "Test*.java" (prefix).
     */
    private final String testNamingConvention;

    /**
     * Up to 3 representative test files (filename → full source).
     * Included as comments in the generated file when no agent instructions exist.
     */
    private final Map<String, String> sampleTests;

    /**
     * Integration/E2E coverage index for this test module.
     * Key   = component name (e.g. "PaymentController", "OrderService")
     * Value = list of integration/E2E test file names that reference this component.
     *
     * <p>Built by {@code RepoContextService.buildCoverageIndex()} by scanning test files
     * for integration test markers ({@code @SpringBootTest}, RestAssured, Gherkin features)
     * and extracting class name references from their content.
     * Empty when {@code contextAvailable == false} (no repo configured).
     */
    private final Map<String, List<String>> coverageIndex;

    // ── Convenience helpers ────────────────────────────────────────────────────

    /** True when at least one agent instruction file was loaded. */
    public boolean hasAgentInstructions() {
        return contextAvailable && agentInstructions != null && !agentInstructions.isEmpty();
    }

    /** True when at least one product expert file was loaded. */
    public boolean hasProductExpert() {
        return contextAvailable && productExpertSections != null && !productExpertSections.isEmpty();
    }

    /**
     * Assembles all loaded product expert files into a single system-prompt section
     * for use in AI generation calls.
     * Returns an empty string when no product expert content is available.
     */
    public String productExpertSystemPrompt() {
        if (!hasProductExpert()) return "";
        StringBuilder sb = new StringBuilder();
        productExpertSections.forEach((name, ctx) -> sb.append(ctx.asSystemPromptSection()));
        return sb.toString();
    }

    /**
     * Returns the repo-level {@code .aiqa/context.md} content, or empty string.
     */
    public String aiqaContextSection() {
        if (repoAiqaContext == null || repoAiqaContext.isBlank()) return "";
        return "=== REPO QA CONTEXT ===\n\n" + repoAiqaContext.trim() + "\n\n";
    }

    /**
     * Returns the list of integration/E2E test files that cover the given component.
     * Returns an empty list when the component has no known coverage.
     */
    public List<String> testsForComponent(String componentName) {
        if (coverageIndex == null) return Collections.emptyList();
        return coverageIndex.getOrDefault(componentName, Collections.emptyList());
    }

    public String effectivePackage(String fallback) {
        return (contextAvailable && basePackage != null) ? basePackage : fallback;
    }

    public String extendsClause() {
        return (contextAvailable && baseTestClass != null)
                ? " extends " + baseTestClass
                : "";
    }

    public String commonImportsBlock() {
        if (!contextAvailable || commonImports == null || commonImports.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("// ── Imports from existing test suite ──\n");
        commonImports.forEach(imp -> sb.append("import ").append(imp).append(";\n"));
        return sb.toString();
    }

    /**
     * Returns a comment block containing the content of all agent instruction files.
     * This is the preferred context header when agent files are present.
     * Falls back to {@link #samplesComment()} when no agent files were loaded.
     */
    public String contextHeader() {
        if (hasAgentInstructions()) {
            StringBuilder sb = new StringBuilder(
                    "/*\n * ════════════════════════════════════════════════════════\n" +
                    " * AGENT INSTRUCTIONS — sourced from .github/agents/ in the\n" +
                    " * target test repo. Follow these conventions exactly.\n" +
                    " * ════════════════════════════════════════════════════════\n");
            agentInstructions.forEach((file, content) -> {
                sb.append(" *\n * ── ").append(file).append(" ──\n");
                // Indent each line with " * " so it stays inside the block comment
                for (String line : content.split("\n")) {
                    sb.append(" * ").append(line.replace("*/", "* /")).append("\n");
                }
            });
            sb.append(" */\n");
            return sb.toString();
        }
        return samplesComment();
    }

    /** Legacy helper — used as fallback inside {@link #contextHeader()}. */
    public String samplesComment() {
        if (!contextAvailable || sampleTests == null || sampleTests.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(
                "/*\n * Context: generated following patterns from existing tests:\n");
        sampleTests.keySet().forEach(name -> sb.append(" *   - ").append(name).append("\n"));
        sb.append(" */\n");
        return sb.toString();
    }
}

