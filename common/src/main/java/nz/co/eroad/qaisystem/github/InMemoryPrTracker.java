package nz.co.eroad.qaisystem.github;
import nz.co.eroad.qaisystem.model.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Component;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
@Slf4j
@Component
@ConditionalOnMissingBean(RedisPrTracker.class)
public class InMemoryPrTracker implements PrTracker {
    private final ConcurrentHashMap<String, PrRecord> store = new ConcurrentHashMap<>();
    @Override public void trackBdd(String b, int n, BddScenario s) {
        store.put(b, PrRecord.builder().branchName(b).prNumber(n).type(PrType.BDD).bddScenario(s).build());
    }
    @Override public void trackTest(String b, int n, TestScript s) {
        store.put(b, PrRecord.builder().branchName(b).prNumber(n).type(PrType.TEST).testScript(s).build());
    }
    @Override public Optional<PrRecord> findByBranch(String b) { return Optional.ofNullable(store.get(b)); }
    @Override public Collection<PrRecord> findAll() { return List.copyOf(store.values()); }
    @Override public void remove(String b) { store.remove(b); }
    @Override public int size() { return store.size(); }
}
