package com.caixa.eodguardian.spring;

import com.caixa.eodguardian.EodGuardian;
import com.caixa.eodguardian.agent.Investigation;
import com.caixa.eodguardian.agent.InvestigationState;
import com.caixa.eodguardian.audit.InMemoryAuditSink;
import com.caixa.eodguardian.common.GuardianConfig;
import com.caixa.eodguardian.common.GuardianException;
import com.caixa.eodguardian.domain.Scenario;
import com.caixa.eodguardian.eod.SimulatedEodEngine;
import com.caixa.eodguardian.patch.DataPatch;
import com.caixa.eodguardian.patch.PatchStatus;
import com.caixa.eodguardian.security.UserPrincipal;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** JUnit 5 version of the headline scenario (failure -> investigation -> maker-checker -> resume). */
class AccountExceptionScenarioTest {

    private static void await(BooleanSupplier c, Duration max) throws InterruptedException {
        long end = System.currentTimeMillis() + max.toMillis();
        while (!c.getAsBoolean()) {
            if (System.currentTimeMillis() > end) throw new AssertionError("timeout");
            Thread.sleep(25);
        }
    }

    private static UserPrincipal user(EodGuardian g, String name) {
        return g.auth.authenticate(g.auth.login(name, "Guardian@2026".toCharArray())).orElseThrow();
    }

    @Test
    void failureIsInvestigatedPatchedUnderMakerCheckerAndResumed() throws Exception {
        try (EodGuardian g = new EodGuardian(GuardianConfig.fastTest(), new InMemoryAuditSink())) {
            UserPrincipal op = user(g, "meera.operator"), maker = user(g, "priya.maker"), checker = user(g, "arjun.checker");
            g.prepareScenario(Scenario.ACCOUNT_EXCEPTION, op);
            g.startEod(op);
            await(() -> {
                Investigation i = g.supervisor.latestFor("CLBATCH");
                return i != null && i.state() == InvestigationState.PATCH_RECOMMENDED && !i.performance().isEmpty();
            }, Duration.ofSeconds(30));
            Investigation inv = g.supervisor.latestFor("CLBATCH");
            assertThat(inv.rootCause().get("entityType")).isEqualTo("CONTRACT");
            assertThat(inv.accuracy().get("rootCauseCorrect")).isEqualTo(true);

            DataPatch p = g.patches.get(inv.patchIds().get(0));
            g.patches.submit(p.patchId(), maker, "Reviewed evidence");
            assertThatThrownBy(() -> g.patches.approve(p.patchId(), maker, "self approve"))
                    .isInstanceOf(GuardianException.class);
            g.patches.approve(p.patchId(), checker, "Verified and approved");
            g.patches.execute(p.patchId(), op, "exec-1");
            assertThat(p.status()).isEqualTo(PatchStatus.EXECUTED);

            g.recovery.resume("CLBATCH", op, "RESUME CLBATCH", "resume-1");
            await(() -> g.engine.runStatus() == SimulatedEodEngine.RunStatus.COMPLETED, Duration.ofSeconds(40));
            assertThat(g.engine.duplicatePostings("CLBATCH")).isZero();
            assertThat(g.compliance.report().get("auditCompletenessPct")).isEqualTo(100.0);
        }
    }
}
