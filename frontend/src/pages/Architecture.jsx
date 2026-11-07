import React, { useEffect, useState } from 'react';
import { api } from '../api.js';
import { Badge, Card, Empty } from '../components/ui.jsx';

const FLOW = [
  ['Monitor', 'EodMonitoringAgent detects FAILED/ABORTED via event stream'],
  ['Triage', 'FailureInvestigationAgent isolates primary vs secondary errors, classifies category'],
  ['Route', 'Supervisor selects specialists by category; skips the rest with a reason'],
  ['Fan-out', 'Account/Contract, Parameter, Reconciliation agents run in parallel (virtual threads)'],
  ['Fan-in', 'RootCauseAnalysisAgent scores candidates on independent corroboration'],
  ['Critic', 'CriticAgent accepts, requests more evidence (reflection) or escalates to a human'],
  ['Recommend', 'DataPatchRecommendationAgent builds a policy-checked single-row patch'],
  ['Approve', 'Maker submits, checker approves (different users)'],
  ['Execute', 'Operator executes via DataPatchExecutionAgent with pre/post validation'],
  ['Resume', 'EodRecoveryAgent gates, resumes from checkpoint and monitors to completion']
];

export default function Architecture({ notify }) {
  const [d, setD] = useState(null);
  useEffect(() => { api.get('/api/agents').then(setD).catch((e) => notify(e.message, 'err')); }, [notify]);
  return (
    <div className="stack">
      <Card title="Workflow" sub="Sequential stages with parallel fan-out, evaluator-critic, reflection and human-in-the-loop gates">
        <div className="flow">
          {FLOW.map(([s, d2], i) => (
            <div key={s} className={`flow-step ${['Approve', 'Execute', 'Resume'].includes(s) ? 'human' : ''}`}>
              <span className="flow-n">{i + 1}</span><b>{s}</b><div className="small muted">{d2}</div>
            </div>
          ))}
        </div>
      </Card>
      <div className="grid-2">
        <Card title="Agents">
          {!d ? <Empty>Loading…</Empty> : (
            <table className="table compact">
              <thead><tr><th>Agent</th><th>Pattern</th><th>Responsibility</th></tr></thead>
              <tbody>{d.agents.map((a) => <tr key={a.agent}><td className="mono small b">{a.agent}</td><td className="small">{a.pattern}</td><td className="small">{a.role}</td></tr>)}</tbody>
            </table>
          )}
        </Card>
        <Card title="Investigation state machine" sub="Illegal transitions are rejected by the server">
          {!d ? <Empty>Loading…</Empty> : (
            <table className="table compact">
              <thead><tr><th>State</th><th>Allowed next states</th></tr></thead>
              <tbody>
                {Object.entries(d.stateMachine).map(([s, next]) => (
                  <tr key={s}><td><Badge value={s} /></td><td className="small">{next.length ? next.map((n) => n.replaceAll('_', ' ')).join(' · ') : 'terminal'}</td></tr>
                ))}
              </tbody>
            </table>
          )}
        </Card>
      </div>
    </div>
  );
}
