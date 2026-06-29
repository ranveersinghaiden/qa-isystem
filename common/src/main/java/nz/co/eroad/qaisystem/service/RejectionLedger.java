package nz.co.eroad.qaisystem.service;

import nz.co.eroad.qaisystem.model.ScenarioClass;

import java.util.Set;

/**
 * Cross-PR learning store. Records which {@link ScenarioClass}es repeatedly drew
 * human rejection for a capability so the coverage planner can force those classes
 * back into the gap matrix even when a change type would not normally require them.
 * Closes the loop the per-PR feedback service cannot: turning one-off rejections
 * into durable, capability-scoped coverage rules.
 */
public interface RejectionLedger {

    /** Records one rejection of {@code scenarioClass} for {@code capability}. */
    void recordRejection(String capability, ScenarioClass scenarioClass);

    /** Scenario classes that have recurred (≥ threshold) in rejections for the capability. */
    Set<ScenarioClass> recurringClasses(String capability);
}
