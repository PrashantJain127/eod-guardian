import React, { useEffect, useMemo, useState } from 'react';
import { api, fmt, newKey } from '../api.js';
import { Badge, Card, Checks, Empty, Timeline, ConfirmField, Icon } from '../components/ui.jsx';

function Action({ label, perm, can, allowed, reason, onClick, kind = '', busy }) {
  const hasPerm = can(perm);
  const disabled = busy || !hasPerm || !allowed;
  const why = !hasPerm ? `Requires ${perm}` : !allowed ? reason : '';
  return (
    <button className={`btn ${kind}`} disabled={disabled} onClick={onClick} title={why}>
      {label}{why && <span className="why">{why}</span>}
    </button>
  );
}

export default function Patches({ dash, focus, setFocus, can, user, notify, go }) {
  const [patches, setPatches] = useState([]);
  const [selId, setSelId] = useState(null);
  const [comment, setComment] = useState('');
  const [busy, setBusy] = useState(false);
  const execKey = useMemo(() => newKey('exec'), [selId]); // one idempotency key per patch view

  const counts = JSON.stringify(dash.patchCounts);
  useEffect(() => { api.get('/api/patches').then(setPatches).catch((e) => notify(e.message, 'err')); }, [counts, notify]);
  useEffect(() => { if (focus.patchId) setSelId(focus.patchId); }, [focus.patchId]);
  useEffect(() => { if (!selId && patches.length) setSelId(patches[0].patchId); }, [patches, selId]);
  const p = patches.find((x) => x.patchId === selId);

  const act = async (verb, body) => {
    setBusy(true);
    try {
      const r = await api.post(`/api/patches/${p.patchId}/${verb}`, body);
      setPatches((ps) => ps.map((x) => (x.patchId === r.patchId ? r : x)));
      setComment('');
      notify(`${p.patchId}: ${r.status.replaceAll('_', ' ').toLowerCase()}`);
    } catch (e) {
      notify(e.message, 'err');
    } finally { setBusy(false); }
  };

  const me = user.username;
  return (
    <div className="split">
      <div className="split-list">
        <Card title="Data patches" sub="AI-recommended, human-controlled">
          {patches.length === 0 ? <Empty>No patch recommendations yet</Empty> : patches.map((x) => (
            <button key={x.patchId} className={`list-item ${x.patchId === selId ? 'active' : ''}`} onClick={() => { setSelId(x.patchId); setFocus({ patchId: x.patchId }); }}>
              <div className="row between"><span className="mono b">{x.patchId}</span><Badge value={x.status} /></div>
              <div className="small">{x.title}</div>
              <div className="small muted">{x.table} · risk <Badge value={x.riskLevel} /></div>
            </button>
          ))}
        </Card>
      </div>
      <div className="split-detail stack">
        {!p ? <Card><Empty>Select a patch</Empty></Card> : (
          <>
            <Card className="hero">
              <div className="row between wrap gap">
                <div>
                  <div className="eyebrow">{p.patchId} · {p.investigationId} · {p.programId}</div>
                  <h2>{p.title}</h2>
                  <div className="row gap wrap">
                    <Badge value={p.status} /> <span className="small">Risk</span> <Badge value={p.riskLevel} />
                    <span className="small muted">recommended by {p.recommendedBy} · {p.confidence}% confidence</span>
                    <button className="link small" onClick={() => go('investigations', { investigationId: p.investigationId })}>open investigation</button>
                  </div>
                </div>
                <div className="maker-checker">
                  <div className={`mc-step ${p.maker ? 'done' : ''}`}><span>Maker</span><b>{p.maker || '—'}</b></div>
                  <Icon name="arrow" />
                  <div className={`mc-step ${p.checker ? 'done' : ''}`}><span>Checker</span><b>{p.checker || '—'}</b></div>
                  <Icon name="arrow" />
                  <div className={`mc-step ${p.executor ? 'done' : ''}`}><span>Executor</span><b>{p.executor || '—'}</b></div>
                </div>
              </div>
            </Card>

            <div className="grid-2">
              <Card title="Proposed change" sub={`${p.operation} ${p.table} — exactly one row`}>
                <div className="sub-h">Affected record</div>
                <div className="mono small">{Object.entries(p.key).map(([k, v]) => `${k} = ${v}`).join('  ·  ')}</div>
                <table className="table compact diff mt">
                  <thead><tr><th>Column</th><th>Existing value</th><th>Proposed value</th></tr></thead>
                  <tbody>
                    {p.changes.map((c) => (
                      <tr key={c.column}><td className="mono b">{c.column}</td><td className="mono old">{fmt.val(c.currentValue)}</td><td className="mono new">{fmt.val(c.proposedValue)}</td></tr>
                    ))}
                  </tbody>
                </table>
                <div className="sub-h">SQL (bind variables; execution uses the structured patch, not this text)</div>
                <pre className="code">{p.sqlPreview}</pre>
                <div className="sub-h">Rollback</div>
                <pre className="code small">{p.rollbackSqlPreview}</pre>
              </Card>
              <Card title="Business justification">
                <div className="sub-h">What is wrong</div><p>{p.whatIsWrong}</p>
                <div className="sub-h">Affected</div><p>{p.affectedEntity}</p>
                <div className="sub-h">Why this correction</div><p>{p.justification}</p>
                <div className="sub-h">Expected impact</div><p>{p.expectedImpact}</p>
                <div className="sub-h">Downstream accounting / reconciliation implications</div>
                <ul className="small">{p.downstreamImplications.map((d, i) => <li key={i}>{d}</li>)}</ul>
                <div className="sub-h">Risk assessment</div>
                <ul className="small">{p.riskNotes.map((d, i) => <li key={i}>{d}</li>)}</ul>
                <div className="sub-h">Alternatives considered</div>
                <ul className="small muted">{p.alternatives.map((d, i) => <li key={i}>{d}</li>)}</ul>
              </Card>
            </div>

            <div className="grid-3">
              <Card title="Pre-patch validation"><Checks items={p.preChecks} /></Card>
              <Card title="Post-patch validation"><Checks items={p.postChecks} /></Card>
              <Card title="Policy & critic review" sub={`Critic verdict: ${p.criticReview?.verdict || '—'}`}>
                <Checks items={p.policyChecks} nameKey="rule" />
              </Card>
            </div>

            <Card title="Authorisation" sub="Maker-checker with segregation of duties. Each action is recorded in the audit trail.">
              <ConfirmField label="Comment / reason (mandatory for approval, rejection and rollback)" value={comment} onChange={setComment}
                placeholder="e.g. RM confirmed settlement account change with customer" multiline />
              <div className="row gap wrap mt">
                <Action label="Submit for approval" perm="PATCH_SUBMIT" can={can} busy={busy} allowed={p.status === 'RECOMMENDED'}
                  reason={`Patch is ${p.status}`} onClick={() => act('submit', { comment: comment || 'Submitted after review of evidence' })} kind="btn-primary" />
                <Action label="Approve" perm="PATCH_APPROVE" can={can} busy={busy} allowed={p.status === 'PENDING_APPROVAL' && p.maker !== me && comment.trim().length >= 5}
                  reason={p.status !== 'PENDING_APPROVAL' ? `Patch is ${p.status}` : p.maker === me ? 'Maker cannot approve' : 'Enter a comment'}
                  onClick={() => act('approve', { comment })} kind="btn-ok" />
                <Action label="Reject" perm="PATCH_APPROVE" can={can} busy={busy}
                  allowed={['PENDING_APPROVAL', 'APPROVED'].includes(p.status) && comment.trim().length >= 5}
                  reason={comment.trim().length < 5 ? 'Enter a reason' : `Patch is ${p.status}`} onClick={() => act('reject', { comment })} kind="btn-bad" />
                <Action label="Execute patch" perm="PATCH_EXECUTE" can={can} busy={busy}
                  allowed={p.status === 'APPROVED' && p.maker !== me && p.checker !== me}
                  reason={p.status !== 'APPROVED' ? `Patch is ${p.status}` : 'Executor must differ from maker & checker'}
                  onClick={() => act('execute', { idempotencyKey: execKey })} kind="btn-primary" />
                <Action label="Rollback" perm="PATCH_ROLLBACK" can={can} busy={busy} allowed={p.status === 'EXECUTED' && comment.trim().length >= 5}
                  reason={p.status !== 'EXECUTED' ? `Patch is ${p.status}` : 'Enter a reason'} onClick={() => act('rollback', { comment })} kind="btn-bad" />
              </div>
              {p.executionResult && (
                <div className={`alert ${p.executionResult.success ? 'alert-ok' : 'alert-bad'} mt`}>
                  <b>Execution:</b> {p.executionResult.message} <span className="muted small">(idempotency key {p.executionIdempotencyKey || '—'})</span>
                </div>
              )}
              {p.status === 'EXECUTED' && (
                <button className="btn btn-sm mt" onClick={() => go('recovery', { programId: p.programId })}>Go to recovery console <Icon name="arrow" size={14} /></button>
              )}
            </Card>

            {(p.beforeImage?.length > 0 || p.afterImage?.length > 0) && (
              <Card title="Before / after snapshot (captured inside the transaction)">
                <div className="grid-2">
                  <pre className="code small">{JSON.stringify(p.beforeImage, null, 2)}</pre>
                  <pre className="code small">{JSON.stringify(p.afterImage, null, 2)}</pre>
                </div>
              </Card>
            )}
            <Card title="Status history"><Timeline items={p.history} /></Card>
          </>
        )}
      </div>
    </div>
  );
}
