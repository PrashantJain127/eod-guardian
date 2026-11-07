import React, { useEffect, useState } from 'react';
import { api, fmt } from '../api.js';
import { Badge, Card, Kpi, Progress, Empty, Icon } from '../components/ui.jsx';

function ScenarioControl({ can, dash, notify }) {
  const [scenarios, setScenarios] = useState([]);
  const [sel, setSel] = useState('ACCOUNT_EXCEPTION');
  const [busy, setBusy] = useState(false);
  useEffect(() => { api.get('/api/eod/scenarios').then(setScenarios).catch(() => {}); }, []);
  if (!can('EOD_CONTROL')) return null;
  const status = dash.overview.status;
  const run = async (start) => {
    setBusy(true);
    try {
      if (start && status === 'NOT_STARTED' && dash.overview.scenario === sel) await api.post('/api/eod/start', {});
      else if (start) await api.post('/api/eod/start', { scenario: sel });
      else await api.post('/api/eod/prepare', { scenario: sel });
      notify(start ? `EOD started (${sel})` : `Business day prepared for ${sel}`);
    } catch (e) {
      notify(e.message, 'err');
    } finally {
      setBusy(false);
    }
  };
  const desc = scenarios.find((s) => s.id === sel)?.description;
  return (
    <Card title="EOD control" sub="Simulated FLEXCUBE branch 000 — choose a demonstration scenario">
      <select value={sel} onChange={(e) => setSel(e.target.value)} className="full">
        {scenarios.map((s) => <option key={s.id} value={s.id}>{s.title}</option>)}
      </select>
      <div className="row gap wrap mt">
        <button className="btn btn-primary" disabled={busy || status === 'RUNNING'} onClick={() => run(true)}>
          <Icon name="play" size={14} /> Start EOD
        </button>
        <button className="btn" disabled={busy || (status === 'RUNNING' && !can('SCENARIO_ADMIN'))} onClick={() => run(false)}>Reset day</button>
      </div>
      {desc && <div className="muted small mt">{desc}</div>}
    </Card>
  );
}

export default function Monitor(props) {
  const { dash, go } = props;
  const ov = dash.overview;
  const programs = dash.programs;
  const current = programs.find((p) => p.status === 'RUNNING');
  const failed = programs.filter((p) => p.status === 'FAILED' || p.status === 'ABORTED');
  const tone = ov.status === 'COMPLETED' ? 'ok' : ov.status === 'HALTED' ? 'bad' : ov.status === 'RUNNING' ? 'run' : '';

  return (
    <div className="stack">
      <div className="kpis">
        <Kpi label="Business date" value={ov.businessDate} hint={`Branch ${ov.branchCode}`} />
        <Kpi label="EOD status" value={<Badge value={ov.status} />} tone={tone} hint={ov.scenarioTitle} />
        <Kpi label="Total programs" value={ov.totalPrograms} />
        <Kpi label="Completed" value={ov.completed} tone="ok" hint={ov.skipped ? `+${ov.skipped} skipped (not applicable)` : ''} />
        <Kpi label="Running" value={ov.running} tone="run" hint={ov.currentProgram || '—'} />
        <Kpi label="Failed / aborted" value={ov.failed} tone={ov.failed ? 'bad' : ''} />
        <Kpi label="Pending" value={ov.pending} />
        <Kpi label="Duration" value={fmt.ms(ov.durationMs)} hint={ov.startTime ? `since ${fmt.time(ov.startTime)}` : 'not started'} />
      </div>

      {failed.length > 0 && (
        <div className="alert alert-bad row between">
          <div>
            <b>{failed.map((f) => f.programId).join(', ')}</b> {failed[0].status.toLowerCase()} — {failed[0].errorCode}: {failed[0].errorMessage}
          </div>
          <button className="btn btn-sm" onClick={() => go('investigations', { programId: failed[0].programId })}>
            Open investigation <Icon name="arrow" size={14} />
          </button>
        </div>
      )}

      <div className="grid-2-1">
        <Card title="EOD program execution" sub={current ? `Currently executing ${current.programId} — ${current.name}` : 'No program executing'}>
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>#</th><th>Program</th><th>Status</th><th>Start / End</th><th>Duration</th>
                  <th style={{ width: 130 }}>Progress</th><th>Depends on</th><th>Error</th><th>Last success</th>
                </tr>
              </thead>
              <tbody>
                {programs.map((p) => (
                  <tr key={p.programId} className={`${p.status === 'RUNNING' ? 'row-run' : ''} ${p.status === 'FAILED' || p.status === 'ABORTED' ? 'row-bad' : ''}`}>
                    <td className="muted">{p.sequence}</td>
                    <td className="prog-cell">
                      <div className="mono b">{p.programId}</div>
                      <div className="small muted">{p.name}</div>
                    </td>
                    <td>
                      <Badge value={p.status} />
                      {p.delayed && <div className="small warn-text"><Icon name="warn" size={12} /> slow</div>}
                      {p.attempts > 1 && <div className="small muted">attempt {p.attempts}</div>}
                    </td>
                    <td className="mono small nowrap">{fmt.time(p.startTime)}<br /><span className="muted">{fmt.time(p.endTime)}</span></td>
                    <td className="mono small">{p.startTime ? fmt.ms(p.elapsedMs) : '—'}</td>
                    <td>
                      <Progress pct={p.percentComplete} status={p.status} />
                      <div className="small muted">{p.totalUnits ? `${p.processedUnits}/${p.totalUnits} records` : ''}</div>
                    </td>
                    <td className="small mono">{p.dependsOn.join(', ') || '—'}</td>
                    <td className="small">
                      {p.errorCode ? (
                        <button className="link" onClick={() => go('investigations', { programId: p.programId })}>
                          <b className="bad-text">{p.errorCode}</b>
                        </button>
                      ) : '—'}
                      {p.errorMessage && <div className="small muted clamp">{p.errorMessage}</div>}
                    </td>
                    <td className="small muted">
                      {p.lastSuccess ? <>{p.lastSuccess.businessDate}<br />{p.lastSuccess.durationSeconds}s</> : '—'}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Card>
        <div className="stack">
          <ScenarioControl {...props} />
          <Card title="Monitoring agent alerts" sub="Failures, delays, dependency violations, completion">
            {dash.alerts.length === 0 ? <Empty>No alerts</Empty> : (
              <ul className="alerts">
                {dash.alerts.slice(0, 12).map((a, i) => (
                  <li key={i} className={`alert-item sev-${a.severity}`}>
                    <div className="row between"><Badge value={a.severity} /><span className="muted small mono">{fmt.time(a.at)}</span></div>
                    <div className="small">{a.message}</div>
                  </li>
                ))}
              </ul>
            )}
          </Card>
          <Card title="Investigations">
            {dash.investigations.length === 0 ? <Empty>None yet</Empty> : dash.investigations.map((i) => (
              <button key={i.investigationId} className="list-item" onClick={() => go('investigations', { investigationId: i.investigationId })}>
                <div className="row between"><span className="mono b">{i.investigationId}</span><Badge value={i.state} /></div>
                <div className="small muted">{i.programId} · {i.errorCode} {i.confidence ? `· ${i.confidence}% confidence` : ''}</div>
                {i.rootCauseTitle && <div className="small">{i.rootCauseTitle}</div>}
              </button>
            ))}
          </Card>
        </div>
      </div>
    </div>
  );
}
