import React from 'react';

const PATHS = {
  pulse: 'M3 12h4l3-8 4 16 3-8h4',
  search: 'M11 4a7 7 0 1 0 0 14 7 7 0 0 0 0-14zm10 17-5.2-5.2',
  patch: 'M14 4l6 6-10 10H4v-6L14 4zm-3 3 6 6',
  restart: 'M4 12a8 8 0 1 0 2.3-5.7M4 4v5h5',
  shield: 'M12 3l8 3v6c0 5-3.5 8-8 9-4.5-1-8-4-8-9V6l8-3zm-3 9 2 2 4-4',
  gauge: 'M4 18a8 8 0 1 1 16 0M12 18l4-6',
  nodes: 'M6 6h4v4H6zM14 14h4v4h-4zM14 4h4v4h-4zM10 8h4M16 8v6M8 10v6h6',
  check: 'M5 12.5l4.5 4.5L19 7',
  x: 'M6 6l12 12M18 6 6 18',
  warn: 'M12 4l9 16H3L12 4zm0 6v4m0 3v.5',
  play: 'M7 5v14l11-7z',
  clock: 'M12 4a8 8 0 1 0 0 16 8 8 0 0 0 0-16zm0 4v4l3 2',
  arrow: 'M5 12h14m-5-5 5 5-5 5',
  bolt: 'M13 3 5 14h6l-1 7 8-11h-6z'
};

export function Icon({ name, size = 18 }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2"
      strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      <path d={PATHS[name] || PATHS.check} />
    </svg>
  );
}

const STATUS_CLASS = {
  COMPLETED: 'ok', RUNNING: 'run', PENDING: 'idle', FAILED: 'bad', ABORTED: 'bad', SKIPPED: 'skip',
  NOT_STARTED: 'idle', HALTED: 'bad',
  RECOMMENDED: 'info', PENDING_APPROVAL: 'warn', APPROVED: 'run', REJECTED: 'bad', EXECUTED: 'ok',
  EXECUTION_FAILED: 'bad', ROLLED_BACK: 'skip', SUPERSEDED: 'skip',
  DETECTED: 'bad', INVESTIGATING: 'run', REFLECTING: 'run', ROOT_CAUSE_IDENTIFIED: 'info', NEEDS_HUMAN_REVIEW: 'warn',
  PATCH_RECOMMENDED: 'info', NO_PATCH_REQUIRED: 'info', AWAITING_APPROVAL: 'warn', PATCH_EXECUTED: 'ok',
  READY_TO_RESUME: 'ok', RESUMED: 'run', CLOSED: 'ok', ERROR: 'bad',
  LOW: 'ok', MEDIUM: 'warn', HIGH: 'bad', CRITICAL: 'bad', WARNING: 'warn', INFO: 'info',
  ACCEPTED: 'ok', NEEDS_MORE_EVIDENCE: 'warn', FIT_FOR_HUMAN_APPROVAL: 'ok', BLOCKED: 'bad', SKIPPED_AGENT: 'skip'
};

export function Badge({ value, kind }) {
  if (value === null || value === undefined) return <span className="muted">—</span>;
  const k = kind || STATUS_CLASS[value] || 'idle';
  return <span className={`badge badge-${k}`}>{String(value).replaceAll('_', ' ')}</span>;
}

export function Card({ title, actions, children, className = '', sub }) {
  return (
    <div className={`card ${className}`}>
      {(title || actions) && (
        <div className="card-head">
          <div>
            {title && <h3>{title}</h3>}
            {sub && <div className="muted small">{sub}</div>}
          </div>
          {actions && <div className="card-actions">{actions}</div>}
        </div>
      )}
      {children}
    </div>
  );
}

export function Kpi({ label, value, tone, hint }) {
  return (
    <div className={`kpi ${tone ? 'kpi-' + tone : ''}`}>
      <div className="kpi-label">{label}</div>
      <div className="kpi-value">{value}</div>
      {hint && <div className="kpi-hint">{hint}</div>}
    </div>
  );
}

export function Progress({ pct, status }) {
  return (
    <div className="progress" title={`${pct}%`}>
      <div className={`progress-bar pb-${STATUS_CLASS[status] || 'idle'}`} style={{ width: `${Math.max(0, Math.min(100, pct))}%` }} />
      <span>{pct}%</span>
    </div>
  );
}

export function Empty({ children }) {
  return <div className="empty">{children}</div>;
}

export function Checks({ items, nameKey = 'name' }) {
  if (!items?.length) return <Empty>No checks</Empty>;
  return (
    <ul className="checks">
      {items.map((c, i) => {
        const passed = c.passed;
        const cls = passed === true ? 'pass' : passed === false ? (c.blocking === false ? 'soft' : 'fail') : 'pending';
        return (
          <li key={i} className={`check check-${cls}`}>
            <span className="check-icon"><Icon name={passed === true ? 'check' : passed === false ? 'x' : 'clock'} size={14} /></span>
            <div>
              <div className="check-name">{c[nameKey] || c.rule || c.check}{c.blocking === false && <span className="muted small"> (advisory)</span>}</div>
              <div className="check-detail">{c.detail || c.observed || c.description}</div>
              {c.sql && <code className="check-sql">{c.sql}</code>}
            </div>
          </li>
        );
      })}
    </ul>
  );
}

/** Renders the small markdown subset produced by the summariser (**bold**, "- " bullets, paragraphs). */
export function MiniMarkdown({ text }) {
  if (!text) return null;
  const blocks = text.split(/\n\n+/);
  const inline = (s) => s.split(/(\*\*[^*]+\*\*)/g).map((part, i) =>
    part.startsWith('**') ? <strong key={i}>{part.slice(2, -2)}</strong> : <React.Fragment key={i}>{part}</React.Fragment>);
  return (
    <div className="md">
      {blocks.map((b, i) => {
        const lines = b.split('\n');
        const bullets = lines.filter((l) => l.trim().startsWith('- '));
        if (bullets.length && bullets.length >= lines.length - 1) {
          const head = lines.find((l) => !l.trim().startsWith('- '));
          return (
            <div key={i}>
              {head && <p>{inline(head)}</p>}
              <ul>{bullets.map((l, j) => <li key={j}>{inline(l.trim().slice(2))}</li>)}</ul>
            </div>
          );
        }
        return <p key={i}>{inline(b)}</p>;
      })}
    </div>
  );
}

export function KeyVals({ data }) {
  return (
    <dl className="kv">
      {Object.entries(data).map(([k, v]) => (
        <React.Fragment key={k}>
          <dt>{k}</dt>
          <dd>{v === null || v === undefined ? '—' : typeof v === 'object' ? JSON.stringify(v) : String(v)}</dd>
        </React.Fragment>
      ))}
    </dl>
  );
}

export function Timeline({ items }) {
  return (
    <ol className="timeline">
      {items.map((t, i) => (
        <li key={i}>
          <span className="tl-dot" />
          <div>
            <div><Badge value={t.to || t.status} /> <span className="muted small">{t.at ? new Date(t.at).toLocaleTimeString([], { hour12: false }) : ''}</span></div>
            <div className="small">{t.actor} — {t.note || t.comment}</div>
          </div>
        </li>
      ))}
    </ol>
  );
}

export function ConfirmField({ label, value, onChange, placeholder, multiline }) {
  return (
    <label className="field">
      <span>{label}</span>
      {multiline
        ? <textarea value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} rows={2} />
        : <input value={value} onChange={(e) => onChange(e.target.value)} placeholder={placeholder} />}
    </label>
  );
}
