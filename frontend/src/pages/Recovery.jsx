import React, { useEffect, useMemo, useState } from 'react';
import { api, fmt, newKey } from '../api.js';
import { Badge, Card, Checks, Empty, Progress, ConfirmField, Icon, Kpi } from '../components/ui.jsx';

export default function Recovery({ dash, focus, can, notify, go }) {
  const candidates = useMemo(() => {
    const ids = new Set();
    dash.programs.filter((p) => p.status === 'FAILED' || p.status === 'ABORTED' || p.attempts > 1).forEach((p) => ids.add(p.programId));
    dash.investigations.forEach((i) => ids.add(i.programId));
    return [...ids];
  }, [dash]);
  const [programId, setProgramId] = useState(null);
  const [assessment, setAssessment] = useState(null);
  const [confirm, setConfirm] = useState('');
  const [busy, setBusy] = useState(false);
  const resumeKey = useMemo(() => newKey('resume'), [programId, assessment?.investigationId]);

  useEffect(() => { if (focus.programId) setProgramId(focus.programId); }, [focus.programId]);
  useEffect(() => { if (!programId && candidates.length) setProgramId(candidates[0]); }, [candidates, programId]);

  const program = dash.programs.find((p) => p.programId === programId);
  const refreshKey = `${program?.status}-${program?.attempts}-${JSON.stringify(dash.patchCounts)}`;
  useEffect(() => {
    if (!programId) return;
    api.get(`/api/recovery/${programId}`).then(setAssessment).catch((e) => notify(e.message, 'err'));
  }, [programId, refreshKey, notify]);

  const resume = async () => {
    setBusy(true);
    try {
      const r = await api.post(`/api/recovery/${programId}/resume`, { confirmation: confirm, idempotencyKey: resumeKey });
      notify(r.replayed ? 'Resume already performed (idempotent replay)' : `${programId} resumed from checkpoint ${r.result.checkpoint}`);
      setConfirm('');
    } catch (e) { notify(e.message, 'err'); } finally { setBusy(false); }
  };

  const ov = dash.overview;
  if (!candidates.length) return <Card><Empty>No failed programs. The recovery console activates when an EOD program fails or aborts.</Empty></Card>;
  const a = assessment;
  const ready = a?.ready;
  const required = a?.requiredConfirmation;
  const downstream = dash.programs.filter((p) => a?.downstream?.includes(p.programId));

  return (
    <div className="stack">
      <div className="row gap wrap">
        {candidates.map((c) => (
          <button key={c} className={`chip ${c === programId ? 'active' : ''}`} onClick={() => setProgramId(c)}>{c}</button>
        ))}
      </div>
      <div className="kpis">
        <Kpi label="Failed program" value={<span className="mono">{programId}</span>} hint={program?.name} />
        <Kpi label="Program status" value={<Badge value={program?.status} />} hint={program?.attempts > 1 ? `attempt ${program.attempts}` : ''} />
        <Kpi label="Checkpoint" value={`${program?.processedUnits ?? 0}/${program?.totalUnits ?? 0}`} hint="records committed" />
        <Kpi label="Recovery readiness" value={<Badge value={program?.status === 'COMPLETED' ? 'COMPLETED' : ready ? 'READY' : 'NOT READY'} kind={program?.status === 'COMPLETED' || ready ? 'ok' : 'bad'} />} />
        <Kpi label="EOD status" value={<Badge value={ov.status} />} hint={`${ov.completed} completed · ${ov.pending} pending`} />
      </div>

      <div className="grid-2-1">
        <Card title="Recovery gates" sub={a?.checksAtResume && program?.status !== 'FAILED' && program?.status !== 'ABORTED'
          ? `As evaluated by the EOD Recovery Agent at resume (${fmt.time(a.resumedAt)}) — all passed`
          : 'Evaluated by the EOD Recovery Agent — all must pass before resumption'}>
          {!a ? <Empty>Assessing…</Empty> : <Checks items={a.checksAtResume && program?.status !== 'FAILED' && program?.status !== 'ABORTED' ? a.checksAtResume : a.checks} nameKey="check" />}
          {a?.patchId && (
            <div className="small mt">Patch <button className="link" onClick={() => go('patches', { patchId: a.patchId })}>{a.patchId}</button> — <Badge value={a.patchStatus} /></div>
          )}
        </Card>
        <div className="stack">
          <Card title="Authorised resume" sub="EOD_OPERATOR only · explicit confirmation · idempotent">
            {program?.status === 'COMPLETED' ? (
              <div className="alert alert-ok"><Icon name="check" size={14} /> {programId} completed after resume{a?.resumedAt ? ` (resumed ${fmt.time(a.resumedAt)})` : ''}.</div>
            ) : (
              <>
                <p className="small">Resuming restarts <b>{programId}</b> from record {(program?.processedUnits ?? 0) + 1}. Committed records are not re-processed and posting keys prevent duplicate accounting entries.</p>
                <ConfirmField label={`Type "${required || '…'}" to confirm`} value={confirm} onChange={setConfirm} placeholder={required} />
                <button className="btn btn-primary mt" disabled={busy || !can('EOD_RESUME') || !ready || confirm.trim() !== required} onClick={resume}
                  title={!can('EOD_RESUME') ? 'Requires EOD_RESUME' : !ready ? 'Recovery gates not satisfied' : ''}>
                  <Icon name="restart" size={14} /> Resume {programId}
                </button>
                {!can('EOD_RESUME') && <div className="muted small mt">Your role cannot resume EOD. Sign in as an EOD operator.</div>}
              </>
            )}
          </Card>
          <Card title="Resumed program status">
            {program && (
              <>
                <div className="row between"><span className="mono b">{program.programId}</span><Badge value={program.status} /></div>
                <Progress pct={program.percentComplete} status={program.status} />
                <div className="small muted">{program.processedUnits}/{program.totalUnits} records · {fmt.ms(program.elapsedMs)}</div>
              </>
            )}
          </Card>
        </div>
      </div>

      <Card title="Downstream programs" sub="Released automatically once the resumed program completes">
        {downstream.length === 0 ? <Empty>None</Empty> : (
          <div className="downstream">
            {downstream.map((p) => (
              <div key={p.programId} className="ds-item">
                <div className="row between"><span className="mono b">{p.programId}</span><Badge value={p.status} /></div>
                <Progress pct={p.percentComplete} status={p.status} />
              </div>
            ))}
          </div>
        )}
        <div className={`alert mt ${ov.status === 'COMPLETED' ? 'alert-ok' : 'alert-info'}`}>
          Final EOD status: <Badge value={ov.status} /> {ov.status === 'COMPLETED' && <>— completed at {fmt.time(ov.endTime)} in {fmt.ms(ov.durationMs)}</>}
        </div>
      </Card>
    </div>
  );
}
