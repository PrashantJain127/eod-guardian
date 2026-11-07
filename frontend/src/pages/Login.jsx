import React, { useEffect, useState } from 'react';
import { api } from '../api.js';
import { Icon } from '../components/ui.jsx';

export default function Login({ onLogin }) {
  const [users, setUsers] = useState([]);
  const [username, setUsername] = useState('');
  const [password, setPassword] = useState('');
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    api.get('/api/auth/users').then((u) => {
      setUsers(u);
      if (u.length) setUsername(u[0].username);
    }).catch(() => {});
  }, []);

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const r = await api.post('/api/auth/login', { username, password });
      onLogin(r.user);
    } catch (err) {
      setError(err.message);
    } finally {
      setBusy(false);
      setPassword('');
    }
  };

  return (
    <div className="login">
      <div className="login-panel">
        <div className="brand brand-lg">
          <div className="brand-mark"><Icon name="check" size={22} /></div>
          <div>
            <div className="brand-name">EOD Guardian</div>
            <div className="brand-sub">Intelligent FLEXCUBE EOD monitoring, investigation &amp; controlled recovery</div>
          </div>
        </div>
        <form onSubmit={submit} className="login-form">
          <label className="field">
            <span>User</span>
            <select value={username} onChange={(e) => setUsername(e.target.value)}>
              {users.map((u) => <option key={u.username} value={u.username}>{u.displayName} — {u.roles.join(', ')}</option>)}
            </select>
          </label>
          <label className="field">
            <span>Password</span>
            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="current-password" required />
          </label>
          {error && <div className="alert alert-bad">{error}</div>}
          <button className="btn btn-primary" disabled={busy || !password}>{busy ? 'Signing in…' : 'Sign in'}</button>
          <p className="muted small">
            Roles are segregated: investigators analyse, makers submit patches, checkers approve, EOD operators execute and resume,
            auditors review. No single user can complete a data change alone.
          </p>
        </form>
      </div>
      <div className="login-art">
        <div className="art-grid">
          {['Monitor', 'Investigate', 'Recommend', 'Approve', 'Execute', 'Resume'].map((s, i) => (
            <div key={s} className="art-step"><span>{i + 1}</span>{s}</div>
          ))}
        </div>
        <p>Supervisor-worker multi-agent system with evaluator-critic review, human-in-the-loop maker-checker controls and a tamper-evident audit trail.</p>
      </div>
    </div>
  );
}
