import React, { useEffect, useState } from 'react';
import { api, fmt } from '../api.js';
import { Badge, Card, Empty, MiniMarkdown, Timeline, Icon, Checks } from '../components/ui.jsx';

function Table({ rows, cols, empty = 'None' }) {
  if (!rows?.length) return <Empty>{empty}</Empty>;
  return (
    <div className="table-wrap">
      <table className="table compact">
        <thead><tr>{cols.map((c) => <th key={c[0]}>{c[1]}</th>)}</tr></thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={i}>{cols.map((c) => <td key={c[0]} className={c[2] || ''}>{c[3] ? c[3](r[c[0]], r) : fmt.val(r[c[0]])}</td>)}</tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

function Confidence({ value }) {
  const tone = value >= 85 ? 'ok' : value >= 75 ? 'warn' : 'bad';
  return (
    <div className="confidence">
      <div className={`conf-ring conf-${tone}`} style={{ '--p': value }}><span>{value}%</span></div>
      <div className="small muted">confidence</div>
    </div>
  );
}

export default function Investigations({ dash, focus, setFocus, go, can, notify }) {
  const list = dash.investigations;
  const [selId, setSelId] = useState(null);
  const [inv, setInv] = useState(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (focus.investigationId) setSelId(focus.investigationId);
    else if (focus.programId) {
      const m = list.find((i) => i.programId === focus.programId);
      if (m) setSelId(m.investigationId);
    }
  }, [focus, list]);
  useEffect(() => { if (!selId && list.length) setSelId(list[0].investigationId); }, [list, selId]);
  const stateKey = list.find((i) => i.investigationId === selId)?.state;
  useEffect(() => {
    if (!selId) return;
    api.get(`/api/investigations/${selId}`).then(setInv).catch((e) => notify(e.message, 'err'));
  }, [selId, stateKey, notify]);

  const failedNoInv = dash.programs.filter((p) => (p.status === 'FAILED' || p.status === 'ABORTED') && !list.some((i) => i.programId === p.programId && !['CLOSED', 'ERROR'].includes(i.state)));
  const investigate = async (programId) => {
    setBusy(true);
    try {
      const r = await api.post('/api/investigations', { programId });
      setSelId(r.investigationId);
      notify(`Investigation ${r.investigationId} completed`);
    } catch (e) { notify(e.message, 'err'); } finally { setBusy(false); }
  };

  const rc = inv?.rootCause;
  return (
    <div className="split">
      <div className="split-list">
        <Card title="Investigations">
          {failedNoInv.map((p) => (
            <div key={p.programId} className="alert alert-bad small">
              {p.programId} {p.status.toLowerCase()} — no active investigation
              {can('INVESTIGATE') && <button className="btn btn-sm mt" disabled={busy} onClick={() => investigate(p.programId)}>Investigate now</button>}
            </div>
          ))}
          {list.length === 0 ? <Empty>No failures investigated yet. Start a failing scenario from the EOD Monitor.</Empty> : list.map((i) => (
            <button key={i.investigationId} className={`list-item ${i.investigationId === selId ? 'active' : ''}`}
              onClick={() => { setSelId(i.investigationId); setFocus({ investigationId: i.investigationId, programId: null }); }}>
              <div className="row between"><span className="mono b">{i.investigationId}</span><Badge value={i.state} /></div>
              <div className="small muted">{i.programId} · {i.errorCode}</div>
              {i.rootCauseTitle && <div className="small">{i.rootCauseTitle}</div>}
            </button>
          ))}
        </Card>
      </div>
      <div className="split-detail stack">
        {!inv ? <Card><Empty>Select an investigation</Empty></Card> : (
          <>
            <Card className="hero">
              <div className="row between wrap gap">
                <div>
                  <div className="eyebrow">Failure summary · {inv.investigationId}</div>
                  <h2>{inv.programId} — {inv.programName}</h2>
                  <div className="row gap wrap">
                    <Badge value={inv.programStatus} /><span className="mono b bad-text">{inv.errorCode}</span>
                    <span className="small muted">record {inv.failedRecord} · detected {fmt.time(inv.createdAt)} by {inv.triggeredBy}</span>
                  </div>
                  <p className="small">{inv.errorMessage}</p>
                </div>
                <div className="row gap">
                  {rc && <Confidence value={rc.confidence} />}
                  <div className="stack-sm">
                    <Badge value={inv.state} />
                    {inv.patchIds.length > 0 && <button className="btn btn-sm btn-primary" onClick={() => go('patches', { patchId: inv.patchIds[inv.patchIds.length - 1] })}>View patch {inv.patchIds[inv.patchIds.length - 1]}</button>}
                    <button className="btn btn-sm" onClick={() => go('recovery', { programId: inv.programId })}>Recovery console</button>
                  </div>
                </div>
              </div>
            </Card>

            <div className="grid-2">
              <Card title="Root cause analysis" sub={rc ? `Category ${inv.errorCategory} · critic verdict ${rc.criticVerdict}` : ''}>
                {!rc ? <Empty>Analysing…</Empty> : (
                  <>
                    <h4 className="rc-title">{rc.title}</h4>
                    <p>{rc.statement}</p>
                    <div className="sub-h">Primary error</div>
                    <code className="block bad-border">{rc.primaryError}</code>
                    <div className="sub-h">Secondary / cascading errors (discounted)</div>
                    <ul className="small plain">{(rc.secondaryErrors || []).map((s, i) => <li key={i} className="muted">↳ {s}</li>)}</ul>
                    <div className="sub-h">Confidence scoring</div>
                    <ul className="small plain">{(rc.scoring || []).map((s, i) => <li key={i}>{s}</li>)}</ul>
                    {rc.ruledOut?.length > 0 && <>
                      <div className="sub-h">Ruled out</div>
                      <ul className="small plain">{rc.ruledOut.map((s, i) => <li key={i} className="muted">✕ {s}</li>)}</ul>
                    </>}
                  </>
                )}
              </Card>
              <Card title="AI-generated investigation summary" sub={inv.llm?.provider ? `${inv.llm.provider} · ${inv.llm.model} · ${inv.llm.totalTokens} tokens · ${inv.llm.cached ? 'cached' : fmt.ms(inv.llm.latencyMs)}` : ''}>
                {inv.aiSummary ? <MiniMarkdown text={inv.aiSummary} /> : <Empty>Pending</Empty>}
                {inv.llm?.fallbackReason && <div className="alert alert-warn small mt">Fallback to grounded summariser: {inv.llm.fallbackReason}</div>}
                <div className="muted small mt">{inv.llm?.grounding}</div>
              </Card>
            </div>

            <Card title="Supporting evidence">
              <ul className="evidence">{(rc?.supportingEvidence || []).map((e, i) => <li key={i}>{e}</li>)}</ul>
            </Card>

            <div className="grid-2">
              <Card title="Impacted accounts">
                <Table rows={inv.impactedAccounts} cols={[['account', 'Account', 'mono'], ['customer', 'Customer', 'mono'], ['ccy', 'Ccy'], ['recordStat', 'Stat'], ['balance', 'Balance', 'num', fmt.num], ['issue', 'Issue', 'small']]} />
              </Card>
              <Card title="Impacted contracts">
                <Table rows={inv.impactedContracts} cols={[['contract', 'Contract', 'mono'], ['type', 'Type'], ['customer', 'Customer', 'mono'], ['issue', 'Issue', 'small'], ['failing', 'Failing', '', (v) => (v ? <Badge value="FAILING" kind="bad" /> : '')]]} />
              </Card>
            </div>

            <Card title="Parameter & configuration changes" sub="Compared with the approved baseline and today's change log">
              <Table rows={inv.parameterChanges} empty="No parameter drift or changes today"
                cols={[['parameter', 'Parameter', 'mono'], ['baselineValue', 'Baseline', 'mono'], ['currentValue', 'Current', 'mono b'],
                  ['changedBy', 'Changed by'], ['changedOn', 'When', 'small', (v) => (v ? fmt.dateTime(v) : '—')], ['changeTicket', 'Ticket', '', (v) => v || <Badge value="NONE" kind="bad" />],
                  ['selfAuthorised', 'Self-auth', '', (v) => (v ? <Badge value="YES" kind="bad" /> : 'No')], ['assessment', 'Assessment', '', (v) => <Badge value={v} kind={v === 'LIKELY CAUSE' ? 'bad' : v === 'SUSPICIOUS' ? 'warn' : 'skip'} />]]} />
            </Card>

            <div className="grid-2">
              <Card title="Related historical incidents">
                <Table rows={inv.historicalIncidents} empty="No similar incidents in lookback window"
                  cols={[['incidentId', 'Incident', 'mono'], ['businessDate', 'Date'], ['errorCode', 'Error', 'mono'], ['similarity', 'Match', '', (v) => `${v}%`], ['resolution', 'Resolution', 'small']]} />
              </Card>
              <Card title="Downstream program impact">
                <Table rows={inv.downstreamImpact} cols={[['programId', 'Program', 'mono'], ['name', 'Name', 'small'], ['status', 'Status', '', (v) => <Badge value={v} />], ['impact', 'Impact', 'small']]} />
              </Card>
            </div>

            <Card title="Multi-agent execution" sub={inv.routing?.rule}>
              <div className="perf-strip">
                <span><b>{fmt.ms(inv.performance?.endToEndMs)}</b> end-to-end</span>
                <span><b>{inv.performance?.agentInvocations}</b> agent calls</span>
                <span><b>×{inv.performance?.parallelSpeedup}</b> parallel speed-up</span>
                <span><b>{inv.performance?.agentsSkippedByRouting}</b> skipped by routing</span>
                <span><b>{inv.reflectionIterations}</b> reflection</span>
                <span><b>{inv.performance?.dataQueries}</b> data queries</span>
                <span><b>{inv.performance?.llmCalls}</b> LLM call · {inv.performance?.tokens} tokens</span>
              </div>
              <div className="table-wrap">
                <table className="table compact">
                  <thead><tr><th>Agent</th><th>Pass</th><th>Status</th><th>Latency</th><th>Queries</th><th>Result</th></tr></thead>
                  <tbody>
                    {inv.agentRuns.map((a, i) => (
                      <tr key={i} className={a.status === 'SKIPPED' ? 'row-skip' : ''}>
                        <td className="mono small b">{a.agent}</td>
                        <td className="small">{a.iteration === 2 ? 'reflection' : a.iteration === 1 ? 'first' : '—'}</td>
                        <td><Badge value={a.status} kind={a.status === 'COMPLETED' ? 'ok' : a.status === 'SKIPPED' ? 'skip' : 'bad'} /></td>
                        <td className="mono small">{a.status === 'SKIPPED' ? '—' : fmt.ms(a.latencyMs)}</td>
                        <td className="mono small">{a.dataQueries || '—'}</td>
                        <td className="small">{a.summary}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              {inv.criticReviews.map((c, i) => (
                <div key={i} className="critic">
                  <div className="row gap"><Icon name="shield" size={16} /><b>Critic review — {c.iteration === 2 ? 'after reflection' : 'first pass'}</b><Badge value={c.verdict} /></div>
                  <Checks items={c.rules} nameKey="rule" />
                </div>
              ))}
            </Card>

            <div className="grid-2">
              <Card title="Log evidence" sub="Classified by the Failure Investigation Agent">
                <div className="log">
                  {inv.logEvidence.map((l) => (
                    <div key={l.seq} className={`log-line cls-${l.classification}`}>
                      <span className="mono small muted">{fmt.time(l.timestamp)}</span>
                      <span className="tag">{l.classification}</span>
                      <span className="mono small">{l.message}</span>
                    </div>
                  ))}
                </div>
              </Card>
              <Card title="Lifecycle (state machine)">
                <Timeline items={inv.transitions} />
              </Card>
            </div>
          </>
        )}
      </div>
    </div>
  );
}
