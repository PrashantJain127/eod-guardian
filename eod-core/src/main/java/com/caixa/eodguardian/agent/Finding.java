package com.caixa.eodguardian.agent;

import java.util.List;
import java.util.Map;

/**
 * A conclusion produced by a specialist agent.
 *
 * @param category   ROOT_CANDIDATE, CONTRIBUTING, RULED_OUT, INFORMATIONAL
 * @param entityType ACCOUNT, CONTRACT, TRANSACTION, PARAMETER, PROGRAM
 * @param confidence 0..100
 * @param attributes structured facts used downstream (e.g. alternate account, baseline value)
 */
public record Finding(String agent, String category, String entityType, String entityId, String title, String description,
                      String severity, int confidence, List<Evidence> evidence, Map<String, Object> attributes) {

    public boolean isRootCandidate() { return "ROOT_CANDIDATE".equals(category); }
}
