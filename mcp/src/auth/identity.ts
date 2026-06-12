// JWT identity decoding for query construction (assignee=<me>, startedBy=<me>).
//
// We do NOT verify the signature here. The engine validates the token on
// every forwarded /engine-rest call (issuer + audience + signature). Decoding
// the payload locally to fish out `preferred_username` is purely so the MCP
// service can construct the right query parameters before forwarding. If the
// token is tampered, the engine rejects the call — defense lives at the
// engine, simplicity lives here.

interface JwtPayload {
  preferred_username?: string;
  sub?: string;
}

export function decodeBearerUsername(bearer: string): string {
  if (!bearer) return '';
  const token = bearer.replace(/^Bearer\s+/i, '');
  const parts = token.split('.');
  if (parts.length < 2) return '';
  try {
    const payload = JSON.parse(Buffer.from(parts[1], 'base64url').toString('utf8')) as JwtPayload;
    return payload.preferred_username ?? payload.sub ?? '';
  } catch {
    return '';
  }
}
