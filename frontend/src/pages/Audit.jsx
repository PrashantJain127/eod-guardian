import React, { useCallback, useEffect, useState } from 'react';
import { api, fmt } from '../api.js';
import { Badge, Card, Empty, Icon } from '../components/ui.jsx';

const FILTERS = [
  ['businessDate', 'Business date', '2026-10-30'],
  ['programId', 'EOD program', 'CLBATCH'],
  ['entity', 'Account / contract', '000TLCN...'],
  ['user', 'User', 'arjun.checker'],
  ['patchId', 'Patch ID', 'PATCH-0001'],
  ['approval', 'Approval status', 'APPROVED'],
  ['outcome', 'Execution outcome', 'SUCCESS'],
  ['eventType', 'Event type', 'PATCH_'],
  ['q', 'Free text', 'anything']
];

export default function Audit({ notify }) {
  const [f, setF] = useState({});
  const [data, setData] = useState(null);
  const [report, setReport] = useState(null);
  const [open, setOpen] = useState(null);

  const load = useCallback(() => {
    const qs = new URLSearchParams(Object.entries(f).filter(([, v]) => v)).toString();
    api.get(`/api/audit?${qs}`).then(setData).catch((e) => notify(e.message, 'err'));
    api.get('/api/audit/verify').then(setReport).catch(() => {});
  }, [f, notify]);
  useEffect(() => { load(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  return (
    <div className="stack">
      {report && (
        <div className="kpis">
          <div className={`kpi ${report.chain.valid ? 'kpi-ok' : 'kpi-bad'}`}>
            <div className="kpi-label">Hash chain</div>
            <div className="kpi-value"><Icon name={report.chain.valid ? 'shield' : 'warn'} /> {report.chain.valid ? 'Verified' : `Broken at ${report.chain.brokenAt}`}</div>
            <div className="kpi-hint mono">{report.chain.headHash?.slice(0, 20)}…</div>
          </div>
          <div className="kpi"><div className="kpi-label">Audit records</div><div className="kpi-value">{report.records}</div><div className="kpi-hint">{report.chain.sink}</div></div>
          <div className="kpi"><div className="kpi-label">Agent decisions / human actions</div><div className="kpi-value">{report.agentDecisions} / {report.humanActions}</div></div>
          <div className="kpi kpi-ok"><div className="kpi-label">Patch trail completeness</div><div className="kpi-value">{report.auditCompletenessPct}%</div><div className="kpi-hint">{report.completePatchTrails}/{report.executedPatches} executed patches</div></div>
          <div className={`kpi ${report.noAgentExecutedChanges ? 'kpi-ok' : 'kpi-bad'}`}><div className="kpi-label">AI-executed data changes</div><div className="kpi-value">{report.noAgentExecutedChanges ? 'None' : 'DETECTED'}</div></div>
        </div>
      )}
      <Card title="Search audit history" actions={<button className="btn btn-primary btn-sm" onClick={load}>Search</button>}>
        <div className="filters">
          {FILTERS.map(([k, label, ph]) => (
            <label key={k} className="field">
              <span>{label}</span>
              <input value={f[k] || ''} placeholder={ph} onChange={(e) => setF({ ...f, [k]: e.target.value })} onKeyDown={(e) => e.key === 'Enter' && load()} />
            </label>
          ))}
        </div>
      </Card>
      <Card title={data ? `${data.returned} of ${data.total} records` : 'Records'}>
        {!data ? <Empty>Loading…</Empty> : data.records.length === 0 ? <Empty>No matching records</Empty> : (
          <div className="table-wrap">
            <table className="table compact">
              <thead><tr><th>#</th><th>Time</th><th>Event</th><th>Actor</th><th>Program</th><th>Patch / Inv.</th><th>Entities</th><th>Approval</th><th>Outcome</th><th>Reason</th></tr></thead>
              <tbody>
                {data.records.map((r) => (
                  <React.Fragment key={r.auditId}>
                    <tr className="clickable" onClick={() => setOpen(open === r.auditId ? null : r.auditId)}>
                      <td className="mono small muted">{r.seq}</td>
                      <td className="mono small">{fmt.time(r.timestamp)}</td>
                      <td className="mono small b">{r.eventType}</td>
                      <td className="small">{r.actor}<div className="muted">{r.actorRole}</div></td>
                      <td className="mono small">{r.programId || '—'}</td>
                      <td className="mono small">{r.patchId || ''}{r.patchId && r.investigationId ? <br /> : ''}{r.investigationId || (r.patchId ? '' : '—')}</td>
                      <td className="mono small">{r.entityRefs?.join(', ') || '—'}</td>
                      <td className="small">{r.approval || '—'}</td>
                      <td><Badge value={r.outcome} kind={/FAIL|DENIED|BLOCKED|ERROR|REJECT|ABORT/.test(r.outcome || '') ? 'bad' : 'info'} /></td>
                      <td className="small clamp">{r.reason}</td>
                    </tr>
                    {open === r.auditId && (
                      <tr className="detail-row"><td colSpan={10}>
                        <div className="grid-3">
                          <div><div className="sub-h">Before</div><pre className="code small">{JSON.stringify(r.before, null, 2) || '—'}</pre></div>
                          <div><div className="sub-h">After</div><pre className="code small">{JSON.stringify(r.after, null, 2) || '—'}</pre></div>
                          <div><div className="sub-h">Details</div><pre className="code small">{JSON.stringify(r.details, null, 2)}</pre>
                            <div className="mono small muted">prev {r.prevHash.slice(0, 16)}… → hash {r.hash.slice(0, 16)}…</div></div>
                        </div>
                      </td></tr>
                    )}
                  </React.Fragment>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </Card>
    </div>
  );
}
