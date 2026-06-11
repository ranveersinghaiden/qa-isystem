package nz.co.eroad.qaisystem.service;

import nz.co.eroad.qaisystem.model.PrContext;
import nz.co.eroad.qaisystem.model.PullRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts Jira IDs, Jira URLs, Confluence URLs, labels, and product names
 * from PR title, description, and metadata before publishing to Kafka.
 */
@Slf4j
@Service
public class PrContextExtractor {

    private static final Pattern JIRA_ID_PATTERN =
            Pattern.compile("\\b([A-Z]{2,10}-\\d+)\\b");

    private static final Pattern JIRA_LINK_PATTERN =
            Pattern.compile("https?://[^\\s<>\"']+/browse/[A-Z]{2,10}-\\d+[^\\s<>\"']*",
                    Pattern.CASE_INSENSITIVE);

    private static final Pattern CONFLUENCE_LINK_PATTERN =
            Pattern.compile("https?://[^\\s<>\"']+/wiki/[^\\s<>\"']+",
                    Pattern.CASE_INSENSITIVE);

    public PrContext extract(PullRequest pr) {
        log.debug("[PrContextExtractor] Extracting for PR {}", pr.getPrId());

        String combined = buildSearchableText(pr);

        List<String> jiraIds         = mergeUnique(pr.getJiraIds(), extractJiraIds(combined));
        List<String> jiraLinks       = mergeUnique(pr.getJiraLinks(), extractUrls(combined, JIRA_LINK_PATTERN));
        List<String> confluenceLinks = mergeUnique(pr.getConfluenceLinks(), extractUrls(combined, CONFLUENCE_LINK_PATTERN));
        List<String> labels          = pr.getLabels() != null ? new ArrayList<>(pr.getLabels()) : new ArrayList<>();
        List<String> products        = resolveProducts(pr.getProducts(), labels);

        // Supplement jiraIds from any Jira browse URLs that were extracted
        for (String link : jiraLinks) {
            Matcher m2 = JIRA_ID_PATTERN.matcher(link);
            while (m2.find()) {
                String id = m2.group(1);
                if (!jiraIds.contains(id)) jiraIds.add(id);
            }
        }

        PrContext ctx = PrContext.builder()
                .jiraIds(jiraIds.isEmpty()           ? null : jiraIds)
                .jiraLinks(jiraLinks.isEmpty()       ? null : jiraLinks)
                .confluenceLinks(confluenceLinks.isEmpty() ? null : confluenceLinks)
                .labels(labels.isEmpty()             ? null : labels)
                .products(products.isEmpty()         ? null : products)
                .build();

        if (ctx.hasContext()) {
            log.info("[PrContextExtractor] PR {} jira={} confluence={} labels={} products={}",
                    pr.getPrId(), size(ctx.getJiraIds()), size(ctx.getConfluenceLinks()),
                    size(ctx.getLabels()), size(ctx.getProducts()));
        } else {
            log.debug("[PrContextExtractor] No external context for PR {}", pr.getPrId());
        }
        return ctx;
    }

    private String buildSearchableText(PullRequest pr) {
        var sb = new StringBuilder();
        if (pr.getTitle() != null) sb.append(" ").append(pr.getTitle());
        if (pr.getDescription() != null) sb.append(" ").append(pr.getDescription());
        if (pr.getLabels() != null) pr.getLabels().forEach(l -> sb.append(" ").append(l));
        return sb.toString();
    }

    private List<String> extractJiraIds(String text) {
        List<String> r = new ArrayList<>();
        if (text == null || text.isBlank()) return r;
        Matcher m = JIRA_ID_PATTERN.matcher(text);
        while (m.find()) {
            String id = m.group(1);
            if (!r.contains(id)) r.add(id);
        }
        return r;
    }

    private List<String> extractUrls(String text, Pattern pattern) {
        List<String> r = new ArrayList<>();
        if (text == null || text.isBlank()) return r;
        Matcher m = pattern.matcher(text);
        while (m.find()) {
            String url = m.group().replaceAll("[.,;)]+$", "");
            if (!r.contains(url)) r.add(url);
        }
        return r;
    }

    private List<String> resolveProducts(List<String> explicit, List<String> labels) {
        List<String> products = new ArrayList<>();
        if (explicit != null) products.addAll(explicit);
        if (labels != null)
            labels.stream()
                  .filter(l -> l.matches("[a-z0-9][a-z0-9-]*") && !products.contains(l))
                  .forEach(products::add);
        return products;
    }

    private List<String> mergeUnique(List<String> a, List<String> b) {
        List<String> r = new ArrayList<>();
        if (a != null) a.stream().filter(s -> !r.contains(s)).forEach(r::add);
        if (b != null) b.stream().filter(s -> !r.contains(s)).forEach(r::add);
        return r;
    }

    private int size(List<?> l) { return l == null ? 0 : l.size(); }
}

