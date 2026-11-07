import React, { useCallback, useEffect, useRef, useState } from 'react';
import { api } from './api.js';
import Login from './pages/Login.jsx';
import Monitor from './pages/Monitor.jsx';
import Investigations from './pages/Investigations.jsx';
import Patches from './pages/Patches.jsx';
import Recovery from './pages/Recovery.jsx';
import Audit from './pages/Audit.jsx';
import Performance from './pages/Performance.jsx';
import Architecture from './pages/Architecture.jsx';
import { Icon } from './components/ui.jsx';

const PAGES = [
  { id: 'monitor', label: 'EOD Monitor', icon: 'pulse' },
  { id: 'investigations', label: 'Investigation', icon: 'search' },
  { id: 'patches', label: 'Data Patches', icon: 'patch' },
  { id: 'recovery', label: 'Recovery Console', icon: 'restart' },
  { id: 'audit', label: 'Audit Trail', icon: 'shield', perm: 'VIEW_AUDIT' },
  { id: 'performance', label: 'Performance', icon: 'gauge' },
  { id: 'architecture', label: 'Agents', icon: 'nodes' }
];

/** Live dashboard over Server-Sent Events (server pushes every 2 s), with polling fallback. */
function useLiveDashboard(enabled) {
  const [data, setData] = useState(null);
  const [live, setLive] = useState({ mode: 'connecting', at: null });
  useEffect(() => {
    if (!enabled) return undefined;
    let es;
    let poll;
    const onData = (d, mode) => {
      setData(d);
      setLive({ mode, at: Date.now() });
    };
    const startPolling = () => {
      if (poll) return;
      const tick = () => api.get('/api/dashboard').then((d) => onData(d, 'polling')).catch(() => setLive((l) => ({ ...l, mode: 'offline' })));
      tick();
      poll = setInterval(tick, 2000);
    };
    if ('EventSource' in window) {
      es = new EventSource('/api/stream');
      es.addEventListener('dashboard', (e) => onData(JSON.parse(e.data), 'live'));
      es.onerror = () => {
        es.close();
        startPolling();
      };
    } else startPolling();
    return () => {
      if (es) es.close();
      if (poll) clearInterval(poll);
    };
  }, [enabled]);
  return [data, live];
}

export default function App() {
  const [user, setUser] = useState(undefined);
  const [page, setPage] = useState(() => window.location.hash.slice(1) || 'monitor');
  const [focus, setFocus] = useState({});
  const [toasts, setToasts] = useState([]);
  const [dash, live] = useLiveDashboard(!!user);
  const [, forceTick] = useState(0);
  const toastId = useRef(0);

  useEffect(() => {
    api.get('/api/auth/me').then(setUser).catch(() => setUser(null));
  }, []);
  useEffect(() => {
    const h = () => setPage(window.location.hash.slice(1) || 'monitor');
    window.addEventListener('hashchange', h);
    return () => window.removeEventListener('hashchange', h);
  }, []);
  useEffect(() => {
    const t = setInterval(() => forceTick((x) => x + 1), 1000);
    return () => clearInterval(t);
  }, []);

  const notify = useCallback((msg, kind = 'ok') => {
    const id = ++toastId.current;
    setToasts((t) => [...t, { id, msg, kind }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), kind === 'err' ? 7000 : 4000);
  }, []);

  const go = useCallback((p, f = {}) => {
    setFocus((old) => ({ ...old, ...f }));
    window.location.hash = p;
  }, []);

  if (user === undefined) return <div className="boot">Loading…</div>;
  if (!user) return <Login onLogin={setUser} />;

  const can = (perm) => user.permissions?.includes(perm);
  const logout = async () => {
    try { await api.post('/api/auth/logout'); } catch { /* ignore */ }
    setUser(null);
  };
  const ov = dash?.overview;
  const failedCount = ov?.failed || 0;
  const pending = (dash?.patchCounts?.PENDING_APPROVAL || 0) + (dash?.patchCounts?.APPROVED || 0);
  const ctx = { user, can, dash, notify, go, focus, setFocus };
  const age = live.at ? Math.max(0, Math.round((Date.now() - live.at) / 1000)) : null;

  return (
    <div className="shell">
      <aside className="nav">
        <div className="brand">
          <div className="brand-mark"><Icon name="check" /></div>
          <div>
            <div className="brand-name">EOD Guardian</div>
            <div className="brand-sub">Caixa · FLEXCUBE Ops</div>
          </div>
        </div>
        {PAGES.filter((p) => !p.perm || can(p.perm)).map((p) => (
          <button key={p.id} className={`nav-item ${page === p.id ? 'active' : ''}`} onClick={() => go(p.id)}>
            <Icon name={p.icon} />
            <span>{p.label}</span>
            {p.id === 'investigations' && failedCount > 0 && <span className="pill pill-red">{failedCount}</span>}
            {p.id === 'patches' && pending > 0 && <span className="pill pill-amber">{pending}</span>}
          </button>
        ))}
        <div className="nav-foot">
          <div className="muted small">LLM: {dash?.config?.llmProvider || '…'}</div>
          <div className="muted small">{dash?.config?.llmModel}</div>
        </div>
      </aside>
      <main className="main">
        <header className="topbar">
          <div className="topbar-left">
            <h1>{PAGES.find((p) => p.id === page)?.label || 'EOD Monitor'}</h1>
            {ov && (
              <span className="crumb">
                Branch {ov.branchCode} · Business date <b>{ov.businessDate}</b> · Run {ov.runId}
              </span>
            )}
          </div>
          <div className="topbar-right">
            <span className={`live live-${live.mode}`} title="Dashboard refresh: server push every 2 s">
              <i />{live.mode === 'live' ? 'Live' : live.mode === 'polling' ? 'Polling' : live.mode === 'offline' ? 'Offline' : 'Connecting'}
              {age !== null && <em>{age}s ago</em>}
            </span>
            <div className="who">
              <div className="avatar">{user.displayName?.[0]}</div>
              <div>
                <div className="who-name">{user.displayName}</div>
                <div className="who-role">{user.roles.join(', ')}</div>
              </div>
              <button className="btn btn-ghost btn-sm" onClick={logout}>Switch user</button>
            </div>
          </div>
        </header>
        <section className="content">
          {!dash ? <div className="card">Connecting to live EOD feed…</div> : (
            <>
              {page === 'monitor' && <Monitor {...ctx} />}
              {page === 'investigations' && <Investigations {...ctx} />}
              {page === 'patches' && <Patches {...ctx} />}
              {page === 'recovery' && <Recovery {...ctx} />}
              {page === 'audit' && can('VIEW_AUDIT') && <Audit {...ctx} />}
              {page === 'performance' && <Performance {...ctx} />}
              {page === 'architecture' && <Architecture {...ctx} />}
            </>
          )}
        </section>
      </main>
      <div className="toasts">
        {toasts.map((t) => <div key={t.id} className={`toast toast-${t.kind}`}>{t.msg}</div>)}
      </div>
    </div>
  );
}
