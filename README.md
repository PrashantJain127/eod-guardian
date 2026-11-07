# EOD Guardian

EOD Guardian is a multi-agent system for Oracle FLEXCUBE end-of-day (EOD) operations. It does five things:

- Shows the status of every EOD program on a live dashboard, updated every 2 seconds.
- Investigates failed programs automatically.
- Explains the root cause, with the evidence behind it.
- Recommends a data patch, then puts it through maker-checker approval before anyone can execute it.
- Resumes the failed program safely, without processing records twice or posting duplicate accounting entries.

AI agents never change banking data on their own. Every change needs a human maker, a different human checker and a third human operator. Every step is written to a tamper-evident audit trail.

---

## 1. Quick start

**You need JDK 21 or later.** Node 18+ is only needed if you want to rebuild the user interface (UI); the built UI is already included.

### Option A: Spring Boot runtime (recommended)

```bash
./gradlew :eod-spring:bootRun                  # http://localhost:8080
# or
./gradlew :eod-spring:bootJar && java -jar eod-spring/build/libs/eod-spring-1.0.0.jar
```

Audit records are stored in an H2 database at `./data/eodguardian.mv.db`. To use PostgreSQL instead, see §7.

### Option B: Standalone runtime (no third-party libraries, works offline)

```bash
./gradlew -PcoreOnly :eod-server:run           # http://localhost:8080
# or
./gradlew -PcoreOnly :eod-server:fatJar && java -jar eod-server/build/libs/eod-server-1.0.0-all.jar
```

`-PcoreOnly` leaves out the Spring module. Use it when the build machine cannot reach Maven Central. Both runtimes share the same core, REST API and UI.

### Sign in

| User | Role | Can do |
|---|---|---|
| `ravi.investigator` | INVESTIGATOR | View everything, start an investigation |
| `priya.maker` | MAKER | Investigate, submit patches |
| `arjun.checker` | CHECKER | Approve or reject patches, view audit |
| `meera.operator` | EOD_OPERATOR | Start or reset EOD, execute or roll back approved patches, resume EOD |
| `sanjay.auditor` | AUDITOR | Audit trail, performance metrics |
| `admin` | ADMIN | Scenario administration, audit (cannot approve or execute patches) |

All demo users share one password, taken from `EOD_DEMO_PASSWORD`. If you don't set it, the password is `Guardian@2026`. **Change it** before anyone else uses the system. In production, replace the demo users with your bank's identity provider (see §7).

---

## 2. Demonstration scenarios

1. Sign in as **meera.operator**.
2. On **EOD Monitor**, choose a scenario and click **Start EOD**.

| # | Scenario | What happens |
|---|---|---|
| 1 | **Successful EOD** | All 15 programs run, 4 in parallel at most. `YEPLTR` (year-end only) is SKIPPED. The dashboard updates every 2 seconds and EOD completes. |
| 2 | **Exceptional loan contract** | `CLBATCH` aborts at record 9 of 18 with `CL-SCH-045`. Loan `000TLCN240117001` still debits settlement account `0010045678`, which was closed today. The Monitoring agent detects this, the specialist agents investigate in parallel, and the Supervisor shows the root cause. |
| 3 | **Unexpected parameter change** | `FXREVAL` fails. `REVAL_RATE_TYPE` was changed from `STANDARD` to `SPOT_OLD` by `ops.user7` with no change ticket and no separate approver. The Parameter agent links this change to the failure and recommends restoring the approved value. |
| 4 | **Data patch and approval** | Continues from 2 or 3: |
| | | 1. The AI recommends a patch, showing the current and proposed values. |
| | | 2. **priya.maker** submits it. |
| | | 3. **arjun.checker** approves it. |
| | | 4. **meera.operator** executes it. |
| | | 5. The full trail appears under **Audit Trail**. |
| 5 | **EOD resumption** | On **Recovery Console**, all 9 safety checks (called "gates" in the UI) must pass. You then type `RESUME CLBATCH` and click Resume. The program restarts from where it stopped, and EOD completes with no duplicate postings. |
| + | **Accounting entry mismatch** | `GLRECON` fails. A term-deposit maturity credit was booked at 2,50,000 instead of 25,00,000. The patch is classified HIGH risk because it changes a financial amount, and needs Finance sign-off. |

Screenshots of one full run (scenarios 1, 2, 4 and 5) are in `docs/screenshots/`. To replay the whole flow in a browser, with the server running:

```bash
npm i -D playwright && node scripts/ui-demo.cjs out-dir
```

---

## 3. Architecture

```
┌──────────── React dashboard (eod-ui, built from /frontend) ─────────────┐
│ Monitor · Investigation · Patches · Recovery · Audit · Perf │ live push every 2 s
└────────────────────────────────┬────────────────────────────────────────┘
                REST + SSE        │   (cookie session, CSRF header, RBAC)
     ┌──────────────┬─────────────┴──────────────┐
     │ eod-spring   │  eod-server (JDK HttpServer)│   ← runtime adapters
     └──────────────┴─────────────┬──────────────┘
                         ApiFacade (transport-neutral API)
┌──────────────────────────────────┴─────────────────────────────────────┐
│ eod-core                                                               │
│  SupervisorAgent (orchestrator + state machine)                        │
│   ├─ EodMonitoringAgent ── listens to the EOD engine                   │
│   ├─ FailureInvestigationAgent ─ triage → conditional routing          │
│   ├─ ‖ AccountContractAnalysisAgent  ‖ ParameterConfigAgent            │
│   │  ‖ ReconciliationAgent  (parallel, virtual threads)                │
│   ├─ RootCauseAnalysisAgent (combines results) → CriticAgent           │
│   ├─ DataPatchRecommendationAgent (recommends only, read-only access)  │
│   ├─ LlmGateway → Mock | OpenAI | Azure OpenAI (one summary call)      │
│  PatchService (maker-checker) → DataPatchExecutionAgent (only writer)  │
│  EodRecoveryAgent (safety checks, checkpoint resume, monitoring)       │
│  AuditService (hash-chained) + AuditComplianceAgent                    │
│  FlexcubeGateway ← MockFlexcubeGateway + SimulatedEodEngine            │
└────────────────────────────────────────────────────────────────────────┘
```

| Module | Contents | Dependencies |
|---|---|---|
| `eod-core` | Domain model, simulated FLEXCUBE, EOD engine, agents, controls, API facade | **none** (JDK 21 only) |
| `eod-server` | Standalone runtime: `com.sun.net.httpserver` with virtual threads, SSE, static UI | eod-core |
| `eod-spring` | Spring Boot 3.3 runtime: Spring Web, SseEmitter, JDBC/H2 audit, Actuator | eod-core, Spring |
| `eod-ui` | Built React bundle served from `classpath:/static` | — |
| `frontend` | React 18 + Vite source for the UI | npm |

### Multi-agent patterns used

| Pattern | Where |
|---|---|
| Supervisor / Orchestrator | `SupervisorAgent.investigate()` runs the 9-stage workflow |
| Supervisor-worker, delegating tasks at run time | Specialists receive an `AgentTask` holding only the facts they need |
| Conditional routing | The error category (`DATA_EXCEPTION`, `PARAMETER`, `RECONCILIATION`, `INFRASTRUCTURE`, `UNKNOWN`) decides which agents run. Skipped agents are recorded with the reason. |
| Parallel fan-out / fan-in | Specialists run on virtual threads. The per-investigation speed-up is reported in the UI and metrics. |
| Sequential workflow | Triage → investigate → combine results → critic → recommend → approve → execute → validate → resume |
| Evaluator-critic | `CriticAgent` checks the root cause against 5 acceptance rules, and the patch against policy, validation checks and the root cause |
| Reflection | When the critic asks for more evidence, the Supervisor re-runs with a wider scope: the skipped agents, plus incident history going back 365 days instead of 30. This happens at most once. |
| Human-in-the-loop | Maker → checker → operator for every patch, plus a typed confirmation for every resume |
| State machine | `InvestigationState` defines the allowed transitions. The server rejects illegal ones. |

### How the root cause is chosen

1. `FailureInvestigationAgent` sorts each log line into one of: primary error, stack frame, symptom, secondary error or cascading error.
2. Specialists produce `Finding`s. Each finding has a category (root candidate, contributing, ruled out, or informational), a confidence score and typed `Evidence` from five sources: LOG, DATABASE, PARAMETER, HISTORY and RECONCILIATION.
3. `RootCauseAnalysisAgent` scores each candidate. It adds points when:
   - the entity is named in the primary error,
   - an identical past incident exists,
   - the program ran cleanly before today (a regression),
   - another agent corroborates it,
   - more independent evidence sources support it.
4. Errors that are only consequences of the primary error are discounted, and appear under "Secondary / cascading errors".
5. The LLM is called **once**, to write the narrative from the structured evidence. Findings and patches are deterministic. The LLM cannot invent a cause or a patch. If the provider is unreachable, a built-in summariser takes over and writes the narrative from the same evidence.

---

## 4. Banking controls

| Control | How it is enforced |
|---|---|
| Role-based access control | `Role` → `Permission` on every API call |
| Segregation of duties | Checker ≠ maker. The executor must differ from both. ADMIN cannot approve or execute. |
| AI never writes | Analysis agents only get a read-only gateway. `DataPatchExecutionAgent` is the only component that writes, it is only reachable from `PatchService.execute`, and it re-checks that the patch is `APPROVED`. |
| Approval before change | The patch status must be APPROVED, and its content hash must match the version the checker approved |
| Patch policy | Allowed tables and columns only. UPDATE only, one row, full primary key, no changes to key columns. Every change carries the value it expects to replace, and those values are verified against live data. Pre- and post-validation checks are required. Any change to a financial amount is HIGH risk. |
| SQL injection | Patches are structured data (table, key, columns) checked against the schema allow-list and applied with bind values. The SQL shown in the UI is for display only. API identifiers are validated by regex. |
| Transaction safety | All updates in a patch commit together or not at all, with an undo log. A blocking post-validation failure triggers automatic compensation. |
| Rollback | The before-image is restored only if the data still holds the patched values. Rollback is blocked once the program has resumed. |
| Idempotency | Patch execution and EOD resume each need an idempotency key, and replays are safe. Postings are keyed by `program:date:record`, so they can't be posted twice. Resume continues from the last checkpoint. |
| Resume safety | 9 checks: program failed, investigation resolved, patch executed, post-validation re-run, failing record dry-run, upstream dependencies complete, no concurrent run, no duplicate postings, reconciliation balanced |
| Explicit confirmation | The operator must type `RESUME <PROGRAM>` |
| Immutable audit | Append-only. Each record's SHA-256 hash includes the previous record's hash. The chain is checked at startup and on demand, and any edited, inserted or deleted record is detected. |
| Before/after snapshots | Row images are captured inside the transaction and stored in the audit record |
| Credentials | Passwords are hashed with PBKDF2-SHA256, and accounts lock after 5 failed attempts. Sessions use a random token in an HttpOnly, SameSite=Strict cookie and last 8 hours. API keys are read from the environment only and never returned by the API. |
| Browser hardening | CSRF header required on writes. CSP, X-Frame-Options and nosniff headers set. Request bodies are capped at 64 KB. |

---

## 5. Configuration

Every setting is an environment variable. You can also pass it as a system property, for example `-Deod.llm.provider=azure`.

| Variable | Default | Purpose |
|---|---|---|
| `EOD_PORT` | 8080 | HTTP port |
| `EOD_DATA_DIR` | `./data` | Audit journal / H2 database location |
| `EOD_DEMO_PASSWORD` | `Guardian@2026` | Password for the demo users |
| `EOD_SPEED` | 1.0 | How fast simulated EOD runs (2.0 = twice as fast) |
| `EOD_SIM_IO_LATENCY_MS` | 60 | Simulated database round-trip time |
| `EOD_BUSINESS_DATE` | 2026-10-30 | Simulated business date |
| `EOD_AUTO_INVESTIGATE` | true | Investigate failures automatically |
| `EOD_LLM_PROVIDER` | `auto` | `auto` \| `mock` \| `openai` \| `azure` |
| `OPENAI_API_KEY` / `OPENAI_MODEL` / `OPENAI_BASE_URL` | — / `gpt-4o-mini` / `https://api.openai.com` | OpenAI, or any OpenAI-compatible gateway |
| `AZURE_OPENAI_ENDPOINT` / `AZURE_OPENAI_API_KEY` / `AZURE_OPENAI_DEPLOYMENT` / `AZURE_OPENAI_API_VERSION` | — / — / `gpt-4o-mini` / `2024-10-21` | Azure OpenAI |
| `EOD_LLM_INPUT_COST_PER_1K` / `EOD_LLM_OUTPUT_COST_PER_1K` | 0.00015 / 0.0006 | USD per 1,000 tokens, used to report cost |

With `auto`, the system uses Azure if only the Azure settings are present, OpenAI if `OPENAI_API_KEY` is set, and otherwise the built-in summariser. This means the system works with no API key at all.

Example (Azure OpenAI):

```bash
export EOD_LLM_PROVIDER=azure
export AZURE_OPENAI_ENDPOINT=https://<resource>.openai.azure.com
export AZURE_OPENAI_API_KEY=<from your key vault>
export AZURE_OPENAI_DEPLOYMENT=gpt-4o-mini
./gradlew :eod-spring:bootRun
```

---

## 6. Testing and performance

```bash
./gradlew -PcoreOnly check                          # build + 9 end-to-end scenario/control tests (offline)
./gradlew :eod-spring:test                          # JUnit 5 + SpringBootTest (needs Maven Central)
./gradlew -PcoreOnly :eod-server:loadTest -Pusers=50 -Pseconds=20   # against a running server
```

**Scenario suite** (`ScenarioTestSuite`, 9 tests, all passing):

- the 5 demonstration scenarios plus the reconciliation break
- segregation of duties
- rejection and re-investigation
- rollback
- replaying execute and resume with the same key
- resume safety checks
- tamper detection
- API authentication, CSRF protection and role checks
- parallel speed-up under simulated I/O

**Load test result** (`docs/loadtest-result.txt`): 50 concurrent users, 776 requests per second, 0 errors. Response times p50 51 ms, p95 108 ms, p99 200 ms. This was measured on a 4-vCPU sandbox with simulated I/O.

**Measured in-app** (Performance page and `GET /api/metrics`):

- tokens and LLM calls per investigation, and cost per successful investigation
- end-to-end time and time per agent (p50, p95, p99)
- how busy each agent was, redundant agent calls, and duplicate triggers that were merged
- parallel speed-up, dashboard push time, and time to detect a failure
- API latency by route and throughput
- recovery success rate
- root-cause and patch accuracy, measured against each scenario's known answer
- audit completeness

**Optimisations built in:**

- Routing skips agents that aren't relevant.
- Investigation starts with a narrow first pass and only widens it when the critic asks.
- Duplicate failure triggers are merged into one investigation.
- One cached LLM call per investigation.
- Agents receive only the context they need.
- Specialists run in parallel on virtual threads.

---

## 7. Connecting to real FLEXCUBE

- **`FlexcubeGateway`** is the only port to FLEXCUBE data. To connect for real, implement it with JDBC against a **read-only** FLEXCUBE schema. Use a separate, narrowly granted account for `applyInTransaction`, or point it at an approved FLEXCUBE maintenance API. The table and column names in the mock already follow FLEXCUBE UBS names (`STTM_CUST_ACCOUNT`, `CLTB_ACCOUNT_MASTER`, `CSTB_PARAM`, `CYTM_RATES`, `ACTB_DAILY_LOG`, …).
- **`SimulatedEodEngine`** stands in for the real batch. To use live data, feed it from the real EOD tables (`AETB_EOC_PROGRAMS`, `AETB_EOC_RUNCHART`, `AETB_EOC_BRANCHES`) and the batch logs. Resume would then call the bank's approved EOD restart mechanism.
- **`AuthService`** holds the demo users. Replace it with LDAP, Active Directory or OIDC, and map directory groups to `Role`.
- **Audit storage:** use PostgreSQL with `schema.sql`. Grant the application account INSERT and SELECT only on `EOD_AUDIT`.

---

## 8. Project layout

```
eod-core/src/main/java/com/caixa/eodguardian/
  agent/            Supervisor, Monitoring agent, Investigation, state machine
  agent/workers/    Failure, Account/Contract, Parameter, Reconciliation, RCA, Critic, Patch rec/exec agents
  api/              ApiFacade (all REST routes), request/response
  audit/            AuditService (hash chain), sinks, AuditComplianceAgent
  eod/              Program catalogue, SimulatedEodEngine, logs
  flexcube/         FlexcubeGateway port, MockFlexcubeGateway, SimulatedFlexcubeData
  llm/              Provider abstraction, Mock, OpenAI/Azure, LlmGateway
  patch/            DataPatch, PatchPolicy, PatchService (maker-checker), validations
  recovery/         EodRecoveryAgent
  security/         Roles, permissions, AuthService
eod-core/src/test/  ScenarioTestSuite
eod-server/         Standalone runtime + LoadTest
eod-spring/         Spring Boot runtime + JUnit tests
frontend/           React source  →  eod-ui/ (built bundle)
docs/screenshots/   UI run of the demonstration scenarios
```

## 9. REST API (summary)

| Method | Path | Permission |
|---|---|---|
| POST | `/api/auth/login`, `/api/auth/logout` · GET `/api/auth/me` | — |
| GET | `/api/dashboard`, `/api/eod/programs`, `/api/eod/programs/{id}/logs`, `/api/eod/scenarios` | VIEW_DASHBOARD |
| GET | `/api/stream` (SSE, pushes every 2 s) | logged in |
| POST | `/api/eod/prepare`, `/api/eod/start` `{scenario}` | EOD_CONTROL |
| GET/POST | `/api/investigations[/{id}]`, POST `{programId}` | VIEW / INVESTIGATE |
| GET | `/api/patches[/{id}]` | VIEW_DASHBOARD |
| POST | `/api/patches/{id}/submit` | PATCH_SUBMIT |
| POST | `/api/patches/{id}/approve`, `/reject` `{comment}` | PATCH_APPROVE |
| POST | `/api/patches/{id}/execute` `{idempotencyKey}` | PATCH_EXECUTE |
| POST | `/api/patches/{id}/rollback` `{comment}` | PATCH_ROLLBACK |
| GET | `/api/recovery/{program}` | VIEW_DASHBOARD |
| POST | `/api/recovery/{program}/resume` `{confirmation, idempotencyKey}` | EOD_RESUME |
| GET | `/api/audit?businessDate=&programId=&entity=&user=&patchId=&approval=&outcome=&eventType=&q=`, `/api/audit/verify` | VIEW_AUDIT |
| GET | `/api/metrics`, `/api/agents`, `/api/health` | VIEW_DASHBOARD / public |

All non-GET calls need the header `X-Requested-With: EODGuardian`.
