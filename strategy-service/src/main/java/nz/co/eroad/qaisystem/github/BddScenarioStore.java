package nz.co.eroad.qaisystem.github;

import nz.co.eroad.qaisystem.model.BddScenario;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * In-memory store that keeps a BddScenario alive until the GitHub BDD review
 * PR is merged and the webhook fires.
 *
 * Lifecycle:
 *   1. put()          - TestPrService registers the scenario after opening the GitHub PR.
 *                       Key = source branch name (e.g. qa/bdd/PR-xxx-abc123).
 *   2. findByBranch() - GitHubWebhookController looks it up on pull_request merged event.
 *   3. remove()       - webhook controller removes after handing off to codegen pipeline.
 */
@Slf4j
@Component
public class BddScenarioStore {

    private final Map<String, BddScenario> store = new ConcurrentHashMap<>();

    public void put(String branchName, BddScenario scenario) {
        store.put(branchName, scenario);
        log.debug("[BddScenarioStore] Stored scenario '{}' for branch '{}'",
                scenario.getScenarioId(), branchName);
    }

    public Optional<BddScenario> findByBranch(String branchName) {
        return Optional.ofNullable(store.get(branchName));
    }

    public void remove(String branchName) {
        BddScenario removed = store.remove(branchName);
        if (removed != null) {
            log.debug("[BddScenarioStore] Removed scenario '{}' for branch '{}'",
                    removed.getScenarioId(), branchName);
        }
    }

    /** Number of pending BDD scenarios waiting for human review & merge. */
    public int size() {
        return store.size();
    }
}
