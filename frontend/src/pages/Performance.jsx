import React, { useEffect, useState } from 'react';
import { api, fmt } from '../api.js';
import { Card, Empty, Kpi } from '../components/ui.jsx';

function TimerTable({ timers, label }) {
  const rows = Object.entries(timers || {}).filter(([, v]) => v);
  if (!rows.length) return <Empty>No samples yet</Empty>;
  return (
    <div className="table-wrap">
      <table className="table compact">
        <thead><tr><th>{label}</th><th className="num">Count</th><th className="num">Mean</th><th className="num">p50</th><th className="num">p95</th><th className="num">p99</th><th className="num">Max</th></tr></thead>
        <tbody>
          {rows.map(([k, v]) => (
            <tr key={k}>
              <td className="mono small">{k}</td><td className="num">{v.count}</td><td className="num">{v.meanMs}</td>
              <td className="num">{v.p50Ms}</td><td className="num">{v.p95Ms}</td><td className="num">{v.p99Ms}</td><td className="num">{v.maxMs}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

const pct = (t) => (t ? `${t.p50Ms} / ${t.p95Ms} / ${t.p99Ms} ms` : '—');

export default function Performance({ dash, notify }) {
  const [m, setM] = useState(null);
  useEffect(() => { api.get('/api/metrics').then(setM).catch((e) => notify(e.message, 'err')); }, [dash.serverTime, notify]);
  if (!m) return <Card><Empty>Loading metrics…</Empty></Card>;
  return (
    <div className="stack">
      <div className="kpis">
        <Kpi label="Investigations" value={`${m.investigations.successful}/${m.investigations.started}`} hint="successful / started" />
        <Kpi label="Investigation latency p50/p95/p99" value={<span className="small-val">{pct(m.investigations.endToEnd)}</span>} />
        <Kpi label="Avg parallel speed-up" value={`×${m.investigations.avgParallelSpeedup}`} hint="fan-out vs sequential" tone="ok" />
        <Kpi label="LLM calls / investigation" value={fmt.num(m.llm.llmCallsPerInvestigation)} hint={`${m.llm.calls} calls · ${m.llm.cacheHits} cache hits`} />
        <Kpi label="Tokens / workflow" value={fmt.num(m.llm.tokensPerInvestigation)} hint={`${m.llm.provider} · ${m.llm.model}`} />
        <Kpi label="Cost / successful investigation" value={`$${m.llm.costPerSuccessfulInvestigationUsd}`} hint={`total $${m.llm.costUsd}`} />
        <Kpi label="Root-cause accuracy" value={m.accuracy.rootCauseAccuracyPct === null ? '—' : `${m.accuracy.rootCauseAccuracyPct}%`} tone="ok" hint={`${m.accuracy.evaluated} evaluated vs ground truth`} />
        <Kpi label="Patch accuracy" value={m.accuracy.patchAccuracyPct === null ? '—' : `${m.accuracy.patchAccuracyPct}%`} tone="ok" />
        <Kpi label="Recovery success" value={m.recovery.successRatePct === null ? '—' : `${m.recovery.successRatePct}%`} hint={`${m.recovery.succeeded}/${m.recovery.resumed} resumed`} />
        <Kpi label="Dashboard push p50/p95/p99" value={<span className="small-val">{pct(m.dashboard.ssePush)}</span>} hint={`target every ${m.dashboard.targetRefreshMs} ms · ${m.dashboard.connectedStreams} streams`} />
        <Kpi label="API throughput" value={`${m.api.throughputPerSec}/s`} hint="rolling 60 s" />
        <Kpi label="Audit completeness" value={`${m.audit.completenessPct}%`} hint={`${m.audit.records} records · chain ${m.audit.chainValid ? 'valid' : 'BROKEN'}`} tone={m.audit.chainValid ? 'ok' : 'bad'} />
      </div>
      <div className="grid-2">
        <Card title="Agent latency" sub="Per-agent timers (all investigations)"><TimerTable timers={m.agents} label="Agent" /></Card>
        <Card title="Efficiency & safety counters">
          <dl className="kv">
            <dt>Agent invocations</dt><dd>{m.investigations.agentInvocations}</dd>
            <dt>Redundant invocations</dt><dd>{m.investigations.redundantInvocations}</dd>
            <dt>Duplicate triggers coalesced</dt><dd>{m.investigations.duplicateTriggersCoalesced}</dd>
            <dt>LLM cache hits / fallbacks</dt><dd>{m.llm.cacheHits} / {m.llm.fallbacks}</dd>
            <dt>Prompt / completion tokens</dt><dd>{m.llm.promptTokens} / {m.llm.completionTokens}</dd>
            <dt>Failure detection latency</dt><dd>{pct(m.monitor.detectionLatency)}</dd>
            <dt>Resume blocked by gates</dt><dd>{m.recovery.blocked}</dd>
            <dt>Duplicate resumes prevented</dt><dd>{m.recovery.duplicateResumePrevented}</dd>
            <dt>Duplicate postings prevented</dt><dd>{m.recovery.duplicatePostingsPrevented}</dd>
          </dl>
        </Card>
      </div>
      <Card title="API latency by route"><TimerTable timers={m.api.routes} label="Route" /></Card>
    </div>
  );
}
