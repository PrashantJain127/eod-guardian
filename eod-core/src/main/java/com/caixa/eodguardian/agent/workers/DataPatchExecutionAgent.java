package com.caixa.eodguardian.agent.workers;

import com.caixa.eodguardian.common.GuardianException;
import com.caixa.eodguardian.common.Maps;
import com.caixa.eodguardian.flexcube.FlexcubeGateway;
import com.caixa.eodguardian.metrics.MetricsRegistry;
import com.caixa.eodguardian.patch.DataPatch;
import com.caixa.eodguardian.patch.PatchExecutor;
import com.caixa.eodguardian.patch.PatchStatus;
import com.caixa.eodguardian.patch.ValidationCheck;

import java.util.*;

/**
 * H. Data Patch Execution Agent - the only component with write access. It is invoked solely by
 * {@code PatchService.execute} after maker-checker approval, and:
 * runs blocking pre-checks, applies the change atomically with an optimistic precondition,
 * runs post-checks, and compensates automatically if any post-check fails.
 */
public class DataPatchExecutionAgent implements PatchExecutor {

    private final FlexcubeGateway gateway;
    private final MetricsRegistry metrics;

    public DataPatchExecutionAgent(FlexcubeGateway gateway, MetricsRegistry metrics) {
        this.gateway = gateway;
        this.metrics = metrics;
    }

    public String name() { return "DataPatchExecutionAgent"; }

    @Override
    public Outcome apply(DataPatch p) {
        long t0 = System.nanoTime();
        try {
            if (p.status() != PatchStatus.APPROVED)   // defence in depth: never execute an unapproved patch
                return new Outcome(false, false, "Refused: patch is " + p.status() + ", not APPROVED", Map.of());
            List<Map<String, Object>> pre = new ArrayList<>();
            for (ValidationCheck c : p.preChecks()) {
                ValidationCheck.Result r = c.run();
                pre.add(c.toMap());
                if (!r.passed() && c.blocking())
                    return new Outcome(false, false, "Pre-check failed, nothing applied: " + c.name() + " (" + r.observed() + ")",
                            Maps.of("preChecks", pre));
            }
            FlexcubeGateway.TransactionResult tx;
            try {
                tx = gateway.applyInTransaction(List.of(new FlexcubeGateway.RowUpdate(p.table(), p.key(), p.expectedValues(), p.proposedValues())));
            } catch (GuardianException e) {
                return new Outcome(false, false, "Transaction rolled back, nothing applied: " + e.getMessage(), Maps.of("preChecks", pre));
            }
            List<Map<String, Object>> post = new ArrayList<>();
            for (ValidationCheck c : p.postChecks()) {
                ValidationCheck.Result r = c.run();
                post.add(c.toMap());
                if (!r.passed() && c.blocking()) {
                    // compensating transaction restores the before-image
                    gateway.applyInTransaction(List.of(new FlexcubeGateway.RowUpdate(p.table(), p.key(), p.proposedValues(), p.expectedValues())));
                    return new Outcome(false, true, "Post-check failed (" + c.name() + ": " + r.observed() + ") - change compensated",
                            Maps.of("preChecks", pre, "postChecks", post, "beforeImage", tx.beforeImages(), "afterImage", tx.beforeImages()));
                }
            }
            return new Outcome(true, false, "Committed " + tx.rowsAffected() + " row(s); all post-validations passed",
                    Maps.of("rowsAffected", tx.rowsAffected(), "preChecks", pre, "postChecks", post,
                            "beforeImage", tx.beforeImages(), "afterImage", tx.afterImages()));
        } finally {
            metrics.time("agent.DataPatchExecutionAgent", System.nanoTime() - t0);
        }
    }

    @Override
    public Outcome revert(DataPatch p) {
        try {
            FlexcubeGateway.TransactionResult tx = gateway.applyInTransaction(List.of(
                    new FlexcubeGateway.RowUpdate(p.table(), p.key(), p.proposedValues(), p.expectedValues())));
            return new Outcome(true, true, "Restored before-image", Maps.of("beforeImage", tx.beforeImages(), "afterImage", tx.afterImages()));
        } catch (GuardianException e) {
            return new Outcome(false, false, "Rollback refused: " + e.getMessage(), Map.of());
        }
    }
}
