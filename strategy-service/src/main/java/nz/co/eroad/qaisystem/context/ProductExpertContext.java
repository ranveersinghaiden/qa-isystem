package nz.co.eroad.qaisystem.context;

import java.util.Map;

/**
 * Holds product-specific knowledge loaded from {@code productExpert/{productName}/}
 * inside the target test repository.
 *
 * <h3>Conventional file layout</h3>
 * <pre>
 * productExpert/
 *   payments/
 *     PRODUCT.md      ← domain knowledge: what payments does, business flows, key entities
 *     PATTERNS.md     ← testing patterns: how payments are tested, common assertions, pitfalls
 *   authentication/
 *     PRODUCT.md
 *     PATTERNS.md
 * .aiqa/
 *   context.md        ← repo-level QA context (overarching conventions, environments, CI notes)
 * </pre>
 *
 * <p>Product expert files are written and maintained by the team.  The AI generation
 * pipeline reads them on every run to produce tests that are grounded in real product
 * knowledge rather than generic patterns.
 *
 * <p>The feedback loop updates these files automatically when a PR reviewer identifies
 * a domain knowledge gap — see {@code PrFeedbackService}.
 */
public record ProductExpertContext(
        /**
         * The directory name under {@code productExpert/}, e.g. {@code "payments"}.
         * Empty string when this is the repo-level (aiqa) context.
         */
        String productName,

        /**
         * Filename → full markdown content.
         * e.g. {@code "PRODUCT.md" → "## Payments\nThe payments module handles..."}.
         */
        Map<String, String> files) {

    /** Returns {@code true} when no files were loaded for this product. */
    public boolean isEmpty() {
        return files == null || files.isEmpty();
    }

    /**
     * Assembles all product expert files into a section suitable for embedding
     * in an AI system prompt.
     */
    public String asSystemPromptSection() {
        if (isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        if (!productName.isBlank()) {
            sb.append("=== PRODUCT EXPERT: ").append(productName.toUpperCase()).append(" ===\n\n");
        }
        files.forEach((name, content) -> {
            sb.append("--- ").append(name).append(" ---\n");
            sb.append(content.trim()).append("\n\n");
        });
        return sb.toString();
    }

    /** Returns the content of {@code PRODUCT.md}, or an empty string if not present. */
    public String productMd() {
        return files == null ? "" : files.getOrDefault("PRODUCT.md", "");
    }

    /** Returns the content of {@code PATTERNS.md}, or an empty string if not present. */
    public String patternsMd() {
        return files == null ? "" : files.getOrDefault("PATTERNS.md", "");
    }

    /** Comma-separated file names — used in log messages. */
    public String fileNames() {
        if (isEmpty()) return "(none)";
        return String.join(", ", files.keySet());
    }
}

