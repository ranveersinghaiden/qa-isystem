package nz.co.eroad.qaisystem.engine;

import nz.co.eroad.qaisystem.model.GitDiff;
import nz.co.eroad.qaisystem.model.ImpactEnvelope;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Builds a lightweight dependency graph from changed files and their
 * import statements to identify transitive impact.
 */
@Slf4j
@Component
public class DependencyGraph {

    private static final Pattern JAVA_IMPORT =
            Pattern.compile("^import\\s+([\\w.]+);$");
    private static final Pattern JAVA_CLASS  =
            Pattern.compile("class\\s+(\\w+)");

    /**
     * Build a graph: changedClass → [classesItImports]
     * and return impacted component descriptors.
     */
    public List<ImpactEnvelope.ImpactedComponent> buildAndAnalyse(
            List<GitDiff> diffs) {

        // Map className → filePath
        Map<String, String> classIndex = buildClassIndex(diffs);

        // Map className → imports (dependencies)
        Map<String, List<String>> dependencyMap = new HashMap<>();
        for (GitDiff diff : diffs) {
            String className = fileToClassName(diff.getFilePath());
            List<String> imports = extractImports(diff);
            dependencyMap.put(className, imports);
        }

        // Build ImpactedComponents
        List<ImpactEnvelope.ImpactedComponent> components = new ArrayList<>();
        for (GitDiff diff : diffs) {
            String className = fileToClassName(diff.getFilePath());
            List<String> directDeps = dependencyMap
                    .getOrDefault(className, List.of());

            // Callers = other changed classes that import this one
            List<String> callers = findCallers(className, dependencyMap);

            ImpactEnvelope.ImpactedComponent comp =
                    ImpactEnvelope.ImpactedComponent.builder()
                            .componentName(className)
                            .filePath(diff.getFilePath())
                            .type(detectComponentType(diff.getFilePath()))
                            .impactScore(calculateImpactScore(diff, callers))
                            .callers(callers)
                            .callees(directDeps.stream()
                                    .filter(classIndex::containsKey)
                                    .collect(Collectors.toList()))
                            .build();
            components.add(comp);
        }

        log.debug("[DependencyGraph] Built {} impacted components", components.size());
        return components;
    }

    public Map<String, List<String>> buildDependencyMap(List<GitDiff> diffs) {
        Map<String, List<String>> graph = new HashMap<>();
        for (GitDiff diff : diffs) {
            String className = fileToClassName(diff.getFilePath());
            graph.put(className, extractImports(diff));
        }
        return graph;
    }

    // ─── Private helpers ───────────────────────────────────────────────────────

    private Map<String, String> buildClassIndex(List<GitDiff> diffs) {
        Map<String, String> index = new HashMap<>();
        for (GitDiff diff : diffs) {
            String className = fileToClassName(diff.getFilePath());
            index.put(className, diff.getFilePath());
        }
        return index;
    }

    private List<String> extractImports(GitDiff diff) {
        List<String> imports = new ArrayList<>();
        for (GitDiff.DiffHunk hunk : diff.getHunks()) {
            for (GitDiff.DiffLine line : hunk.getLines()) {
                Matcher m = JAVA_IMPORT.matcher(line.getContent().trim());
                if (m.matches()) {
                    imports.add(m.group(1));
                }
            }
        }
        return imports;
    }

    private List<String> findCallers(String targetClass,
                                     Map<String, List<String>> depMap) {
        return depMap.entrySet().stream()
                .filter(e -> e.getValue().stream()
                        .anyMatch(imp -> imp.endsWith(targetClass)))
                .map(Map.Entry::getKey)
                .collect(Collectors.toList());
    }

    private ImpactEnvelope.ImpactedComponent.ComponentType detectComponentType(
            String filePath) {
        String lower = filePath.toLowerCase();
        if (lower.contains("controller")) {
            return ImpactEnvelope.ImpactedComponent.ComponentType.CONTROLLER;
        }
        if (lower.contains("service"))   {
            return ImpactEnvelope.ImpactedComponent.ComponentType.SERVICE;
        }
        if (lower.contains("repositor") || lower.contains("dao")) {
            return ImpactEnvelope.ImpactedComponent.ComponentType.REPOSITORY;
        }
        if (lower.contains("model") || lower.contains("entity") ||
                lower.contains("dto")) {
            return ImpactEnvelope.ImpactedComponent.ComponentType.MODEL;
        }
        if (lower.contains("config"))    {
            return ImpactEnvelope.ImpactedComponent.ComponentType.CONFIG;
        }
        if (lower.contains("test") || lower.contains("spec")) {
            return ImpactEnvelope.ImpactedComponent.ComponentType.TEST;
        }
        if (lower.contains("util") || lower.contains("helper")) {
            return ImpactEnvelope.ImpactedComponent.ComponentType.UTILITY;
        }
        return ImpactEnvelope.ImpactedComponent.ComponentType.SERVICE;
    }

    private double calculateImpactScore(GitDiff diff, List<String> callers) {
        double base = Math.min(1.0,
                (diff.getLinesAdded() + diff.getLinesDeleted()) / 200.0);
        double callerBonus = Math.min(0.3, callers.size() * 0.1);
        return Math.min(1.0, base + callerBonus);
    }

    private String fileToClassName(String filePath) {
        String name = filePath;
        if (name.contains("/")) name = name.substring(name.lastIndexOf('/') + 1);
        if (name.contains("."))  name = name.substring(0, name.lastIndexOf('.'));
        return name;
    }
}