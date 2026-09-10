// Decodes the (unverified) payload of a session token issued by
// TokenService.java — format is base64url(JSON payload incl. "exp") + "." +
// base64url(signature). This is NOT a security check (BE re-verifies the
// HMAC signature on every request/handshake); it only lets the FE reason
// locally about whether a token is worth presenting again, e.g. to tell an
// expired session apart from a merely unreachable server.
export function decodeExpiry(token: string): number | null {
  try {
    const payloadB64 = token.split('.')[0];
    if (!payloadB64) return null;
    const base64 = payloadB64.replace(/-/g, '+').replace(/_/g, '/');
    const padded = base64.padEnd(Math.ceil(base64.length / 4) * 4, '=');
    const payload = JSON.parse(atob(padded)) as { exp?: unknown };
    return typeof payload.exp === 'number' ? payload.exp : null;
  } catch {
    return null;
  }
}

// A token whose expiry can't be decoded is treated as not-expired — we only
// want to act (log the user out) when we can positively confirm expiry, not
// on every unparseable or unusual token.
export function isTokenExpired(token: string): boolean {
  const exp = decodeExpiry(token);
  return exp !== null && Date.now() >= exp;
}
