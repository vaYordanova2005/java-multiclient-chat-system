// Same-origin in dev (Vite proxies /api -> BE, see vite.config.ts) and in any
// prod deploy that serves FE + BE from one origin. Set VITE_API_BASE_URL to
// point at a separately-hosted BE instead.
const API_BASE = import.meta.env.VITE_API_BASE_URL ?? '';

export class ApiError extends Error {
  status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

export async function postJson<T>(path: string, body: unknown): Promise<T> {
  const res = await fetch(`${API_BASE}${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  });

  const data = await res.json().catch(() => ({}));

  if (!res.ok) {
    // AuthController's failure shape is always {error: string} — see BE/README.md.
    const message = typeof data?.error === 'string' ? data.error : `Request failed (${res.status})`;
    throw new ApiError(res.status, message);
  }

  return data as T;
}

export function apiBase(): string {
  return API_BASE;
}
