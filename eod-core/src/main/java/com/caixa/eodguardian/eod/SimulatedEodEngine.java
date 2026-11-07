package com.caixa.eodguardian.eod;

import com.caixa.eodguardian.common.GuardianException;
import com.caixa.eodguardian.common.Maps;
import com.caixa.eodguardian.domain.ProgramRunHistory;
import com.caixa.eodguardian.domain.ProgramStatus;
import com.caixa.eodguardian.domain.Scenario;
import com.caixa.eodguardian.flexcube.MockFlexcubeGateway;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.TemporalAdjusters;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simulates the FLEXCUBE EOD batch: programs run in sequence/parallel according to their
 * dependencies, process records with checkpoints, post accounting entries (idempotently) and
 * abort/fail on bad data exactly as the real batch would. Resumption restarts from the checkpoint.
 */
public class SimulatedEodEngine implements AutoCloseable {

    public enum RunStatus { NOT_STARTED, RUNNING, HALTED, COMPLETED }

    private final MockFlexcubeGateway gateway;
    private final EodLogStore logs;
    private final double speed;
    private final List<EodListener> listeners = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService scheduler;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();

    private final Map<String, ProgramRuntime> programs = new LinkedHashMap<>();
    private final Map<String, List<String>> unitLists = new ConcurrentHashMap<>();
    /** Posting ledger: posting key -> number of times posted (must stay at 1). */
    private final Map<String, Integer> postings = new ConcurrentHashMap<>();
    private final AtomicLong duplicatePostingsPrevented = new AtomicLong();
    private final Map<String, Double> slowFactors = new ConcurrentHashMap<>();
    private final Set<String> activeWorkers = ConcurrentHashMap.newKeySet();

    private volatile String runId;
    private volatile Scenario scenario = Scenario.SUCCESS;
    private volatile RunStatus runStatus = RunStatus.NOT_STARTED;
    private volatile LocalDateTime runStart;
    private volatile LocalDateTime runEnd;
    private volatile String startedBy;
    private volatile long generation;
    private volatile int maxParallel = 2;

    public SimulatedEodEngine(MockFlexcubeGateway gateway, EodLogStore logs, double speed) {
        this.gateway = gateway;
        this.logs = logs;
        this.speed = speed <= 0 ? 1.0 : speed;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "eod-scheduler");
            t.setDaemon(true);
            return t;
        });
        scheduler.scheduleAtFixedRate(this::safeSchedule, 100, 100, TimeUnit.MILLISECONDS);
    }

    public void addListener(EodListener l) { listeners.add(l); }

    // ------------------------------------------------------------------ run control

    /** Prepares a fresh business day for the scenario (data reseeded, all programs PENDING). */
    public synchronized void prepare(Scenario sc, LocalDate businessDate) {
        generation++;
        this.scenario = sc;
        gateway.reseed(sc, businessDate);
        logs.clear();
        programs.clear();
        unitLists.clear();
        postings.clear();
        duplicatePostingsPrevented.set(0);
        slowFactors.clear();
        if (sc == Scenario.ACCOUNT_EXCEPTION) slowFactors.put("MMACCR", 2.6); // shows delay detection
        for (EodProgramDef d : EodProgramCatalog.programs()) {
            ProgramRuntime rt = new ProgramRuntime(d);
            gateway.runHistory(d.id()).stream()
                    .filter(h -> h.status() == ProgramStatus.COMPLETED)
                    .max(Comparator.comparing(ProgramRunHistory::businessDate))
                    .ifPresent(rt::lastSuccess);
            programs.put(d.id(), rt);
        }
        runId = "EOD-" + businessDate.toString().replace("-", "") + "-" + Long.toHexString(System.nanoTime() & 0xfffff).toUpperCase();
        runStatus = RunStatus.NOT_STARTED;
        runStart = null;
        runEnd = null;
        startedBy = null;
    }

    public synchronized void start(String user) {
        if (runStatus != RunStatus.NOT_STARTED)
            throw GuardianException.conflict("EOD_ALREADY_STARTED", "EOD run " + runId + " is already " + runStatus);
        maxParallel = readMaxParallel();
        runStatus = RunStatus.RUNNING;
        runStart = LocalDateTime.now();
        startedBy = user;
        logs.log("AEEOD", "INFO", "AEPKS_EOD", "EOD started for branch " + gateway.branchCode() + " business date "
                + gateway.businessDate() + " by " + user + " (run " + runId + ")");
        listeners.forEach(l -> l.onRunStatus(runId, runStatus.name()));
    }

    /** Restarts a FAILED/ABORTED program from its last checkpoint. Only the Recovery Agent calls this. */
    public synchronized void resume(String programId) {
        ProgramRuntime p = program(programId);
        if (!p.status().isTerminalFailure())
            throw GuardianException.conflict("NOT_RESUMABLE", programId + " is " + p.status() + ", only FAILED/ABORTED programs can be resumed");
        if (activeWorkers.contains(programId))
            throw GuardianException.conflict("ALREADY_RUNNING", programId + " already has an active worker");
        ProgramStatus old = p.status();
        p.markRunning(unitLists.getOrDefault(programId, List.of()).size(), true);
        logs.log(programId, "INFO", p.def().plsqlPackage(), "Program RESUMED from checkpoint " + p.processedUnits()
                + "/" + p.totalUnits() + " (attempt " + p.attempts() + ")");
        runStatus = RunStatus.RUNNING;
        fire(p, old, ProgramStatus.RUNNING);
        launch(p, generation);
    }

    // ------------------------------------------------------------------ scheduling

    private void safeSchedule() {
        try { schedule(); } catch (Throwable t) { logs.log("AEEOD", "ERROR", "SCHEDULER", "Scheduler error: " + t); }
    }

    private synchronized void schedule() {
        if (runStatus != RunStatus.RUNNING) return;
        long running = programs.values().stream().filter(p -> p.status() == ProgramStatus.RUNNING).count();
        for (ProgramRuntime p : programs.values()) {
            if (p.status() != ProgramStatus.PENDING) continue;
            boolean depsDone = p.def().dependsOn().stream().allMatch(d -> programs.get(d).status().isDone());
            if (!depsDone) continue;
            if (p.def().periodEndOnly() && !isFinancialYearEnd(gateway.businessDate())) {
                p.markSkipped();
                logs.log(p.id(), "INFO", p.def().plsqlPackage(), "Program SKIPPED - not applicable for " + gateway.businessDate());
                fire(p, ProgramStatus.PENDING, ProgramStatus.SKIPPED);
                continue;
            }
            if (running >= maxParallel) continue;
            List<String> units = units(p.def());
            unitLists.put(p.id(), units);
            p.markRunning(units.size(), false);
            running++;
            logs.log(p.id(), "INFO", p.def().plsqlPackage(), "Program STARTED - " + units.size() + " record(s) to process");
            fire(p, ProgramStatus.PENDING, ProgramStatus.RUNNING);
            launch(p, generation);
        }
        // delay detection input (the Monitoring Agent raises the alert)
        for (ProgramRuntime p : programs.values()) {
            if (p.status() == ProgramStatus.RUNNING)
                p.markDelayed(p.elapsedMillis() > p.def().targetSeconds() * 1000 / speed * 1.6);
        }
        boolean anyRunning = programs.values().stream().anyMatch(p -> p.status() == ProgramStatus.RUNNING);
        boolean anyFailed = programs.values().stream().anyMatch(p -> p.status().isTerminalFailure());
        boolean allDone = programs.values().stream().allMatch(p -> p.status().isDone());
        if (allDone) {
            runStatus = RunStatus.COMPLETED;
            runEnd = LocalDateTime.now();
            logs.log("AEEOD", "INFO", "AEPKS_EOD", "EOD COMPLETED for branch " + gateway.branchCode() + " - new business date "
                    + gateway.businessDate().plusDays(gateway.businessDate().getDayOfWeek().getValue() == 5 ? 3 : 1));
            listeners.forEach(l -> l.onRunStatus(runId, runStatus.name()));
        } else if (!anyRunning && anyFailed) {
            boolean anyStartable = programs.values().stream().anyMatch(p -> p.status() == ProgramStatus.PENDING
                    && p.def().dependsOn().stream().allMatch(d -> programs.get(d).status().isDone()));
            if (!anyStartable) {
                runStatus = RunStatus.HALTED;
                logs.log("AEEOD", "ERROR", "AEPKS_EOD", "EOD HALTED - awaiting operator intervention");
                listeners.forEach(l -> l.onRunStatus(runId, runStatus.name()));
            }
        }
    }

    private int readMaxParallel() {
        try {
            return gateway.findByKey("CSTB_PARAM", Map.of("PARAM_NAME", "EOD_PARALLEL_STREAMS"))
                    .map(r -> Math.max(1, Math.min(4, Integer.parseInt(String.valueOf(r.get("PARAM_VAL"))))))
                    .orElse(2);
        } catch (RuntimeException e) {
            return 2;
        }
    }

    private static boolean isFinancialYearEnd(LocalDate d) {
        if (d.getMonthValue() != 3) return false;
        LocalDate last = d.with(TemporalAdjusters.lastDayOfMonth());
        while (last.getDayOfWeek().getValue() >= 6) last = last.minusDays(1);
        return d.equals(last);
    }

    private void launch(ProgramRuntime p, long gen) {
        activeWorkers.add(p.id());
        workers.submit(() -> {
            try { execute(p, gen); } finally { activeWorkers.remove(p.id()); }
        });
    }

    // ------------------------------------------------------------------ program execution

    private void execute(ProgramRuntime p, long gen) {
        List<String> units = unitLists.getOrDefault(p.id(), List.of());
        double slow = slowFactors.getOrDefault(p.id(), 1.0);
        long paceMs = (long) (p.def().targetSeconds() * 1000 * slow / speed / Math.max(1, units.size()));
        int i = p.processedUnits();
        try {
            for (; i < units.size(); i++) {
                if (gen != generation) return; // run was reset
                if (paceMs > 0) Thread.sleep(paceMs);
                String unit = units.get(i);
                processUnit(p.def(), unit);
                if (p.def().postsAccounting()) post(p.id(), unit);
                p.checkpoint(i + 1);
            }
            synchronized (this) {
                if (gen != generation) return;
                p.markCompleted();
                logs.log(p.id(), "INFO", p.def().plsqlPackage(), "Program COMPLETED - " + units.size() + " record(s) processed in "
                        + p.elapsedMillis() + " ms");
                fire(p, ProgramStatus.RUNNING, ProgramStatus.COMPLETED);
            }
        } catch (EodProgramException ex) {
            synchronized (this) {
                if (gen != generation) return;
                for (String line : ex.traceLines()) logs.log(p.id(), line.startsWith("FATAL") ? "FATAL" : "ERROR", p.def().plsqlPackage(), line);
                p.markFailed(ex.resultingStatus(), ex.errorCode(), ex.getMessage(), ex.recordRef());
                fire(p, ProgramStatus.RUNNING, ex.resultingStatus());
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException ex) {
            synchronized (this) {
                logs.log(p.id(), "FATAL", p.def().plsqlPackage(), "Unhandled exception: " + ex);
                p.markFailed(ProgramStatus.ABORTED, "AEEOD-9999", ex.toString(), units.size() > i ? units.get(i) : null);
                fire(p, ProgramStatus.RUNNING, ProgramStatus.ABORTED);
            }
        }
    }

    private void post(String programId, String unit) {
        String key = programId + ":" + gateway.businessDate() + ":" + unit;
        Integer prev = postings.putIfAbsent(key, 1);
        if (prev != null) duplicatePostingsPrevented.incrementAndGet(); // idempotent: never post twice
    }

    private List<String> units(EodProgramDef d) {
        String today = gateway.businessDate().toString();
        return switch (d.id()) {
            case "AEEOTI", "AEEOFI", "BRDTCHG" -> List.of(gateway.branchCode());
            case "ICACCR" -> gateway.query("STTM_CUST_ACCOUNT", Map.of("RECORD_STAT", "O")).stream()
                    .map(r -> (String) r.get("CUST_AC_NO")).toList();
            case "TDMATP", "TDBOD" -> gateway.query("TDTB_CONTRACT_MASTER", Map.of()).stream()
                    .map(r -> (String) r.get("CONTRACT_REF_NO")).toList();
            case "CLBATCH" -> gateway.query("CLTB_ACCOUNT_MASTER", Map.of("NEXT_DUE_DATE", today, "ACCOUNT_STATUS", "A")).stream()
                    .map(r -> (String) r.get("ACCOUNT_NUMBER")).sorted().toList();
            case "LDACCR" -> gateway.query("CLTB_ACCOUNT_MASTER", Map.of("ACCOUNT_STATUS", "A")).stream()
                    .map(r -> (String) r.get("ACCOUNT_NUMBER")).sorted().toList();
            case "FXREVAL" -> gateway.query("STTM_CUST_ACCOUNT", Map.of()).stream()
                    .filter(r -> !"INR".equals(r.get("CCY"))).map(r -> (String) r.get("CUST_AC_NO")).sorted().toList();
            case "GLRECON" -> gateway.query("ACTB_DAILY_LOG", Map.of()).stream()
                    .map(r -> (String) r.get("TRN_REF_NO")).distinct().sorted().toList();
            default -> {
                List<String> l = new ArrayList<>();
                for (int k = 1; k <= 12; k++) l.add(d.id() + "-B" + String.format("%03d", k));
                yield l;
            }
        };
    }

    /** Record-level business checks performed by the (simulated) PL/SQL batch packages. */
    private void processUnit(EodProgramDef d, String unit) {
        switch (d.id()) {
            case "CLBATCH" -> {
                Map<String, Object> loan = gateway.findByKey("CLTB_ACCOUNT_MASTER", Map.of("BRANCH_CODE", "000", "ACCOUNT_NUMBER", unit))
                        .orElseThrow();
                String settle = (String) loan.get("DR_PROD_AC");
                Optional<Map<String, Object>> ac = gateway.findByKey("STTM_CUST_ACCOUNT", Map.of("BRANCH_CODE", "000", "CUST_AC_NO", settle));
                boolean bad = ac.isEmpty() || !"O".equals(ac.get().get("RECORD_STAT")) || "Y".equals(ac.get().get("AC_STAT_FROZEN"));
                if (bad) {
                    String why = ac.isEmpty() ? "not found" : ("RECORD_STAT=" + ac.get().get("RECORD_STAT") + ", AC_STAT_FROZEN=" + ac.get().get("AC_STAT_FROZEN"));
                    throw new EodProgramException("CL-SCH-045",
                            "Settlement account " + settle + " for loan " + unit + " is closed/invalid for debit (" + why + ")",
                            unit, ProgramStatus.ABORTED, List.of(
                            "CLPKS_BATCH.fn_auto_liqd: processing account " + unit + " component PRINCIPAL/MAIN_INT due " + gateway.businessDate(),
                            "ORA-20001: CL-SCH-045: Settlement account " + settle + " is closed or invalid for debit (" + why + ")",
                            "ORA-06512: at \"FCUBS.CLPKS_LIQD\", line 2214",
                            "ORA-06512: at \"FCUBS.CLPKS_BATCH\", line 1872",
                            "ORA-06502: PL/SQL: numeric or value error: character string buffer too small at \"FCUBS.CLPKS_BATCH.PR_LOG_EXCEPTION\", line 311",
                            "AEEOD-0077: Stream CL suspended - dependent programs LDACCR, FXREVAL will wait",
                            "FATAL AEEOD-0091: Batch CLBATCH aborted for branch 000 after 1 fatal error(s); checkpoint retained"));
                }
            }
            case "FXREVAL" -> {
                Map<String, Object> acc = gateway.findByKey("STTM_CUST_ACCOUNT", Map.of("BRANCH_CODE", "000", "CUST_AC_NO", unit)).orElseThrow();
                String ccy = (String) acc.get("CCY");
                String rateType = gateway.findByKey("CSTB_PARAM", Map.of("PARAM_NAME", "REVAL_RATE_TYPE"))
                        .map(r -> String.valueOf(r.get("PARAM_VAL"))).orElse("STANDARD");
                boolean typeOk = gateway.findByKey("CYTM_RATE_TYPE", Map.of("RATE_TYPE", rateType)).isPresent();
                boolean rateOk = typeOk && gateway.findByKey("CYTM_RATES", Map.of("CCY1", ccy, "CCY2", "INR", "RATE_TYPE", rateType)).isPresent();
                if (!rateOk) {
                    throw new EodProgramException("CY-RATE-404",
                            "Rate for " + ccy + "/INR with rate type " + rateType + " not maintained (REVAL_RATE_TYPE)",
                            unit, ProgramStatus.FAILED, List.of(
                            "CYPKS_REVAL.fn_reval_account: account " + unit + " ccy " + ccy + " rate type " + rateType,
                            "ORA-01403: no data found",
                            "ORA-20001: CY-RATE-404: Rate for " + ccy + "/INR rate type " + rateType + " not maintained",
                            "ORA-06512: at \"FCUBS.CYPKS_REVAL\", line 642",
                            "AEEOD-0077: Stream CY suspended - GLRECON will wait",
                            "FATAL AEEOD-0090: Batch FXREVAL failed for branch 000; revaluation entries for this run were not posted"));
                }
            }
            case "GLRECON" -> {
                List<Map<String, Object>> legs = gateway.query("ACTB_DAILY_LOG", Map.of("TRN_REF_NO", unit));
                BigDecimal dr = BigDecimal.ZERO, cr = BigDecimal.ZERO;
                for (Map<String, Object> l : legs) {
                    BigDecimal amt = new BigDecimal(String.valueOf(l.get("LCY_AMOUNT")));
                    if ("D".equals(l.get("DRCR_IND"))) dr = dr.add(amt); else cr = cr.add(amt);
                }
                if (dr.compareTo(cr) != 0) {
                    throw new EodProgramException("GL-RECON-001",
                            "Debit/Credit mismatch for " + unit + " (Dr " + dr.toPlainString() + " / Cr " + cr.toPlainString()
                                    + ", difference " + dr.subtract(cr).abs().toPlainString() + ")",
                            unit, ProgramStatus.FAILED, List.of(
                            "GLPKS_RECON.fn_check_balancing: TRN_REF_NO " + unit + " legs=" + legs.size(),
                            "GL-RECON-001: Debit/Credit mismatch for TRN_REF_NO " + unit + " Dr " + dr.toPlainString() + " Cr " + cr.toPlainString(),
                            "ORA-20510: GL balancing failed for branch 000",
                            "ORA-06512: at \"FCUBS.GLPKS_RECON\", line 418",
                            "FATAL AEEOD-0090: Batch GLRECON failed for branch 000"));
                }
            }
            default -> { }
        }
    }

    private void fire(ProgramRuntime p, ProgramStatus from, ProgramStatus to) {
        for (EodListener l : listeners) {
            try { l.onProgramStatus(p, from, to); } catch (RuntimeException e) {
                logs.log("AEEOD", "WARN", "LISTENER", "Listener error: " + e.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------ queries

    public synchronized ProgramRuntime program(String id) {
        ProgramRuntime p = programs.get(id == null ? "" : id.toUpperCase(Locale.ROOT));
        if (p == null) throw GuardianException.notFound("Unknown EOD program " + id);
        return p;
    }

    public synchronized List<ProgramRuntime> programs() { return new ArrayList<>(programs.values()); }

    /** Programs that (transitively) depend on the given program. */
    public synchronized List<String> downstreamOf(String programId) {
        List<String> out = new ArrayList<>();
        Deque<String> q = new ArrayDeque<>(List.of(programId));
        while (!q.isEmpty()) {
            String cur = q.poll();
            for (ProgramRuntime p : programs.values())
                if (p.def().dependsOn().contains(cur) && !out.contains(p.id())) { out.add(p.id()); q.add(p.id()); }
        }
        return out;
    }

    public List<String> unitsOf(String programId) { return unitLists.getOrDefault(programId, List.of()); }

    /** Number of posting keys for the program posted more than once (must be 0). */
    public long duplicatePostings(String programId) {
        return postings.entrySet().stream().filter(e -> e.getKey().startsWith(programId + ":") && e.getValue() > 1).count();
    }

    public long postedCount(String programId) {
        return postings.keySet().stream().filter(k -> k.startsWith(programId + ":")).count();
    }

    public long duplicatePostingsPrevented() { return duplicatePostingsPrevented.get(); }

    public boolean isWorkerActive(String programId) { return activeWorkers.contains(programId); }

    /** Re-runs the record-level check for one record without posting (used for recovery dry-run). */
    public void dryRun(String programId, String unit) { processUnit(program(programId).def(), unit); }

    public String runId() { return runId; }
    public Scenario scenario() { return scenario; }
    public RunStatus runStatus() { return runStatus; }
    public LocalDateTime runStart() { return runStart; }
    public LocalDateTime runEnd() { return runEnd; }
    public LocalDate businessDate() { return gateway.businessDate(); }
    public EodLogStore logs() { return logs; }
    public double speed() { return speed; }

    public synchronized Map<String, Object> overview() {
        Map<ProgramStatus, Long> counts = new EnumMap<>(ProgramStatus.class);
        for (ProgramStatus s : ProgramStatus.values()) counts.put(s, 0L);
        for (ProgramRuntime p : programs.values()) counts.merge(p.status(), 1L, Long::sum);
        long durMs = runStart == null ? 0 : java.time.Duration.between(runStart, runEnd != null ? runEnd : LocalDateTime.now()).toMillis();
        ProgramRuntime current = programs.values().stream().filter(p -> p.status() == ProgramStatus.RUNNING)
                .min(Comparator.comparingInt(p -> p.def().sequence())).orElse(null);
        return Maps.of(
                "runId", runId,
                "businessDate", gateway.businessDate(),
                "branchCode", gateway.branchCode(),
                "scenario", scenario,
                "scenarioTitle", scenario.title(),
                "status", runStatus,
                "startedBy", startedBy,
                "startTime", runStart,
                "endTime", runEnd,
                "durationMs", durMs,
                "totalPrograms", programs.size(),
                "completed", counts.get(ProgramStatus.COMPLETED),
                "running", counts.get(ProgramStatus.RUNNING),
                "failed", counts.get(ProgramStatus.FAILED) + counts.get(ProgramStatus.ABORTED),
                "pending", counts.get(ProgramStatus.PENDING),
                "skipped", counts.get(ProgramStatus.SKIPPED),
                "currentProgram", current == null ? null : current.id(),
                "duplicatePostingsPrevented", duplicatePostingsPrevented.get());
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
        workers.shutdownNow();
    }
}
