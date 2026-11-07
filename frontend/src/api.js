// Thin API client. Auth uses the HttpOnly session cookie; every state-changing call carries the
// anti-CSRF header the server requires.
const CSRF = { 'X-Requested-With': 'EODGuardian' };

export class ApiError extends Error {
  constructor(status, code, message) {
    super(message);
    this.status = status;
    this.code = code;
  }
}

async function request(method, path, body) {
  const res = await fetch(path, {
    method,
    credentials: 'same-origin',
    headers: { 'Content-Type': 'application/json', ...(method !== 'GET' ? CSRF : {}) },
    body: body === undefined ? undefined : JSON.stringify(body)
  });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) throw new ApiError(res.status, data?.error, data?.message || res.statusText);
  return data;
}

export const api = {
  get: (p) => request('GET', p),
  post: (p, b = {}) => request('POST', p, b)
};

export function newKey(prefix) {
  const r = (globalThis.crypto && crypto.randomUUID) ? crypto.randomUUID() : Math.random().toString(36).slice(2) + Date.now();
  return `${prefix}-${r}`;
}

export const fmt = {
  time: (t) => (t ? new Date(t).toLocaleTimeString([], { hour12: false }) : '—'),
  dateTime: (t) => (t ? new Date(t).toLocaleString([], { hour12: false }) : '—'),
  ms: (ms) => {
    if (ms === null || ms === undefined) return '—';
    if (ms < 1000) return `${Math.round(ms)} ms`;
    const s = ms / 1000;
    if (s < 60) return `${s.toFixed(1)} s`;
    return `${Math.floor(s / 60)}m ${Math.round(s % 60)}s`;
  },
  num: (n) => (n === null || n === undefined || n === '' ? '—' : Number(n).toLocaleString('en-IN', { maximumFractionDigits: 2 })),
  val: (v) => (v === null || v === undefined ? '—' : typeof v === 'object' ? JSON.stringify(v) : String(v))
};
