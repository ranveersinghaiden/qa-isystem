package nz.co.eroad.qaisystem.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * External context extracted from a Pull Request that enriches test generation
 * across every pipeline stage.
 *
 * <p>Populated by {@code PrContextExtractor} in pr-service and carried unchanged
 * through {@code ImpactEnvelope} → {@code BddScenario} → test code generation.
 *
 * <ul>
 *   <li>{@code jiraIds}        — Jira issue keys (e.g. "VSF-3670"), merged from the
 *       explicit {@code jira_ids} payload field and any keys found by regex in title /
 *       description.</li>
 *   <li>{@code jiraLinks}      — Full Jira browse URLs extracted from the description.</li>
 *   <li>{@code confluenceLinks}— Confluence page URLs extracted from the description.</li>
 *   <li>{@code labels}         — GitHub PR labels / tags (e.g. "bug", "feature",
 *       "payments-team").</li>
 *   <li>{@code products}       — Product area names from the explicit {@code products}
 *       field or inferred from labels.</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PrContext {

    /** Jira issue keys merged from payload field + regex extraction (e.g. ["VSF-3670", "AUTH-12"]). */
    private List<String> jiraIds;

    /** Full Jira browse URLs found in the PR description (e.g. ["https://jira.example.com/browse/VSF-3670"]). */
    private List<String> jiraLinks;

    /** Confluence page URLs found in the PR description. */
    private List<String> confluenceLinks;

    /** GitHub PR labels/tags applied to the PR (e.g. ["bug", "payments", "high-priority"]). */
    private List<String> labels;

    /** Product/domain area names (from explicit products field or inferred from labels). */
    private List<String> products;

    /** True when at least one piece of external context was collected. */
    public boolean hasContext() {
        return hasJira() || hasConfluence() || hasLabels() || hasProducts();
    }

    public boolean hasJira() {
        return (jiraIds != null && !jiraIds.isEmpty())
                || (jiraLinks != null && !jiraLinks.isEmpty());
    }

    public boolean hasConfluence() {
        return confluenceLinks != null && !confluenceLinks.isEmpty();
    }

    public boolean hasLabels() {
        return labels != null && !labels.isEmpty();
    }

    public boolean hasProducts() {
        return products != null && !products.isEmpty();
    }

    /**
     * Formats a concise text summary for inclusion in AI prompts and test comments.
     * Returns an empty string when no external context is available.
     */
    public String asPromptSection() {
        if (!hasContext()) return "";
        var sb = new StringBuilder("=== PR EXTERNAL CONTEXT ===\n");
        if (hasJira()) {
            if (jiraIds != null && !jiraIds.isEmpty())
                sb.append("Jira tickets  : ").append(String.join(", ", jiraIds)).append("\n");
            if (jiraLinks != null && !jiraLinks.isEmpty())
                sb.append("Jira links    : ").append(String.join(" | ", jiraLinks)).append("\n");
        }
        if (hasConfluence())
            sb.append("Confluence    : ").append(String.join(" | ", confluenceLinks)).append("\n");
        if (hasLabels())
            sb.append("PR labels     : ").append(String.join(", ", labels)).append("\n");
        if (hasProducts())
            sb.append("Products      : ").append(String.join(", ", products)).append("\n");
        sb.append("===========================\n");
        return sb.toString();
    }
}

