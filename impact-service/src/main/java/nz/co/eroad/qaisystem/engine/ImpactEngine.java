package nz.co.eroad.qaisystem.engine;

import nz.co.eroad.qaisystem.ai.AIImpactEvaluator;
import nz.co.eroad.qaisystem.model.CoverageReport;
import nz.co.eroad.qaisystem.model.GitDiff;
import nz.co.eroad.qaisystem.model.ImpactEnvelope;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.ChangeType;
import nz.co.eroad.qaisystem.model.ImpactEnvelope.ImpactedComponent;
import nz.co.eroad.qaisystem.model.PullRequest;
import nz.co.eroad.qaisystem.parser.GitDiffParser;
import nz.co.eroad.qaisystem.service.TestCoverageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Phase 1 — Deterministic impact analysis, with optional AI refinement as last resort.
 *
 * Pipeline: parse → dependency graph → change types → risk score
 *           → AI refinement (gray-zone only, if configured) → coverage assessment → envelope
 *
 * Publishes the resulting ImpactEnvelope to ImpactResultsQueue.
 * Strategy-service consumes ImpactResultsQueue as its entry point.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImpactEngine {

    private final GitDiffParser       diffParser;
    private final DependencyGraph     dependencyGraph;
    private final ChangeTypeDetector  changeTypeDetector;
    private final RiskScorer          riskScorer;
    private final TestCoverageService testCoverageService;
    private final AIImpactEvaluator   aiImpactEvaluator;

    public ImpactEnvelope analyze(PullRequest pr) {
        log.info("[ImpactEngine] Analyzing PR '{}'", pr.getPrId());

        // 1. Parse diffs
        List<GitDiff> diffs = Optional.ofNullable(pr.getDiffs())
                .filter(d -> !d.isEmpty())
                .orElseGet(() -> diffParser.parse(pr.getRawDiffContent()));

        // 2. Dependency graph
        List<ImpactedComponent> components = dependencyGraph.buildAndAnalyse(diffs);
        Map<String, List<String>> depMap   = dependencyGraph.buildDependencyMap(diffs);

        // 3. Change types
        List<ChangeType> changeTypes = changeTypeDetector.detect(diffs);

        // 4. Risk scoring
        double riskScore             = riskScorer.score(diffs, changeTypes, components);
        ImpactEnvelope.RiskLevel lvl = riskScorer.toLevel(riskScore);

        // 4b. AI last-resort refinement — only invoked when riskScore is in the
        //     ambiguous gray zone AND the evaluator is configured.  Falls back
        //     silently to the deterministic result on any error.
        ImpactEnvelope.AIInsight aiInsight = null;
        var aiResult = aiImpactEvaluator.evaluate(pr, diffs, changeTypes, components, riskScore);
        if (aiResult.isPresent()) {
            aiInsight = aiResult.get();
            if (aiInsight.isApplied()) {
                double prevScore = riskScore;
                riskScore    = aiInsight.getAdjustedRiskScore();
                lvl          = riskScorer.toLevel(riskScore);
                changeTypes  = mergeChangeTypes(changeTypes, aiInsight.getAddedChangeTypes());
                log.info("[ImpactEngine] AI refinement applied — riskScore {} → {} ({}) | " +
                         "added types: {}",
                        String.format("%.2f", prevScore),
                        String.format("%.2f", riskScore), lvl,
                        aiInsight.getAddedChangeTypes());
            } else {
                log.info("[ImpactEngine] AI ran but found no changes to the deterministic result — " +
                         "reason: {}", aiInsight.getReasoning());
            }
        }

        // 5. Coverage assessment: identify which components need integration/E2E tests.
        //    Level is UNKNOWN here — real coverage (GOOD/PARTIAL/NONE) is determined
        //    downstream in strategy-service by E2ECoverageAnalyzer against the test repo.
        CoverageReport coverage = testCoverageService.assess(components, diffs);

        // 6. Metrics
        int totalAdded   = diffs.stream().mapToInt(GitDiff::getLinesAdded).sum();
        int totalDeleted = diffs.stream().mapToInt(GitDiff::getLinesDeleted).sum();

        List<String> existingTestFiles = diffs.stream()
                .filter(GitDiff::isTestFile)
                .map(GitDiff::getFilePath)
                .collect(Collectors.toList());

        Map<String, String> serviceConfidence = components.stream()
                .collect(Collectors.toMap(
                        ImpactedComponent::getComponentName,
                        c -> c.getImpactScore() >= 0.7 ? "HIGH"
                                : c.getImpactScore() >= 0.4 ? "MEDIUM" : "LOW",
                        (a, b) -> a));

        ImpactEnvelope envelope = ImpactEnvelope.builder()
                .envelopeId(UUID.randomUUID().toString())
                .prId(pr.getPrId())
                .repositoryName(pr.getRepositoryName())
                .analyzedAt(LocalDateTime.now())
                .impactedComponents(components)
                .impactedModules(extractModules(diffs))
                .detectedChangeTypes(changeTypes)
                .overallRiskScore(riskScore)
                .riskLevel(lvl)
                .serviceConfidence(serviceConfidence)
                .coverageReport(coverage)
                .directDependencies(collectDirectDeps(depMap))
                .transitiveDependencies(collectTransitiveDeps(depMap))
                .dependencyGraph(depMap)
                .totalFilesChanged(diffs.size())
                .totalLinesAdded(totalAdded)
                .totalLinesDeleted(totalDeleted)
                .affectedTestFiles((int) diffs.stream().filter(GitDiff::isTestFile).count())
                .existingTestFiles(existingTestFiles)
                .suggestedTestAreas(coverage.getUntestedComponents())
                .changesSummary(buildSummary(pr, diffs, changeTypes, lvl, coverage))
                .aiInsight(aiInsight)   // null when AI disabled/not triggered
                .build();

        log.info("[ImpactEngine] Envelope '{}' → risk={} ({}) coverage={} componentsPendingE2E={} aiApplied={}",
                envelope.getEnvelopeId(),
                String.format("%.2f", riskScore), lvl,
                coverage.getLevel(), coverage.getUntestedComponents().size(),
                aiInsight != null && aiInsight.isApplied());

        return envelope;
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────

    /** Merges AI-detected change types into the existing list without duplicates. */
    private List<ChangeType> mergeChangeTypes(List<ChangeType> existing,
                                              List<ChangeType> additional) {
        if (additional == null || additional.isEmpty()) return existing;
        List<ChangeType> merged = new ArrayList<>(existing);
        additional.stream()
                .filter(ct -> !merged.contains(ct))
                .forEach(merged::add);
        return merged;
    }

    private List<String> extractModules(List<GitDiff> diffs) {
        return diffs.stream()
                .map(GitDiff::getFilePath)
                .map(p -> { String[] parts = p.split("/"); return parts.length > 2 ? parts[1] : "root"; })
                .distinct().collect(Collectors.toList());
    }

    private List<String> collectDirectDeps(Map<String, List<String>> graph) {
        return graph.values().stream().flatMap(Collection::stream).distinct().collect(Collectors.toList());
    }

    private List<String> collectTransitiveDeps(Map<String, List<String>> graph) {
        Set<String> transitive = new HashSet<>();
        for (List<String> deps : graph.values()) {
            for (String dep : deps) {
                if (graph.containsKey(dep)) transitive.addAll(graph.get(dep));
            }
        }
        return new ArrayList<>(transitive);
    }

    private String buildSummary(PullRequest pr, List<GitDiff> diffs,
                                List<ChangeType> types,
                                ImpactEnvelope.RiskLevel level,
                                CoverageReport coverage) {
        return String.format(
                "PR '%s' changed %d files (+%d/-%d). Types: %s. Risk: %s. Coverage: %s (components pending E2E scan: %s).",
                pr.getTitle(), diffs.size(),
                diffs.stream().mapToInt(GitDiff::getLinesAdded).sum(),
                diffs.stream().mapToInt(GitDiff::getLinesDeleted).sum(),
                types, level, coverage.getLevel(),
                coverage.getUntestedComponents());
    }
}

