---
name: deployment-conventions
description: Rules for writing, changing or reviewing deployment files: docker-compose*.yml, deploy/, frontend/nginx.conf and mobile/nginx.conf with their snippets, esb/routes, keycloak/ realm and themes, traefik/, Dockerfiles and .github/workflows. Use before changing any of them, before a deploy, and when running /review.
---

# Deployment conventions

The rule table for deployment changes. Where a rule is already in `CLAUDE.md` or `docs/security.md`,
the table points there. Rules with a Details section are lessons from a sibling project (roppoc)
that this repository had not recorded yet; D1 and D2 were found missing here and fixed when this skill
was written.

Every rule below comes from a failure that looked healthy: containers up, pipeline green, nothing
logged. Prefer a probe that proves the result to a file that looks right.

Severity: **BLOCKER P1** is a security or data-integrity fault, **BLOCKER P2** a fault that breaks
behaviour, **BLOCKER P3** a quality fault; all three block a commit. **WARNING** needs a stated reason
to skip. **INFO** is advice. `/review` uses these levels.

## Rules

| ID | Severity | Rule | Where |
|---|---|---|---|
| D1 | BLOCKER P2 | `proxy_pass` to a container uses `resolver 127.0.0.11` and a variable, never a literal host | [D1](#d1) |
| D2 | BLOCKER P2 | `index.html` and `env.js` are `no-store`; hashed assets are cacheable; a missing asset is a 404, never the SPA shell | [D2](#d2) |
| D3 | BLOCKER P1 | A `location` with its own `add_header` includes a complete header snippet | `frontend/nginx.conf` header comment; `docs/security.md` §8 |
| D4 | BLOCKER P1 | The CSP allows no inline script; per-deployment origins are written at container start | `frontend/docker/40-runtime-env.sh`; F2 |
| D5 | BLOCKER P1 | `/api/internal/**` answers 404 at every ingress | `docs/security.md` §5, §8 |
| D6 | BLOCKER P1 | The ESB injects `X-Internal-Token` only for callers presenting `X-Bus-Token` | `docs/security.md` §9 |
| D7 | BLOCKER P1 | A port published by the deploy kit is a configurable binding or profile-gated | [D7](#d7) |
| D8 | BLOCKER P1 | No secret literal in the realm export or compose files; realm placeholders are `${VAR}` | `CLAUDE.md` gotchas; `docs/security.md` §12 |
| D9 | BLOCKER P2 | `frontendUrl` and `KC_HOSTNAME` are placeholders; a realm change needs Keycloak recreated | `CLAUDE.md` gotchas |
| D10 | BLOCKER P2 | `keycloak/` and `deploy/keycloak/` copies stay identical | `CLAUDE.md` "The realm is two files" |
| D11 | BLOCKER P2 | Brute-force protection on, permanent lockout off; a password policy fits the seeded users | [D11](#d11) |
| D12 | BLOCKER P2 | Compose merges `ports` by concatenation; narrow a binding in the base file, not an overlay | [D12](#d12) |
| D13 | BLOCKER P2 | Containers run as non-root on pinned images | `docs/security.md` §11 |
| D14 | WARNING | A workflow forwards every secret it needs and fails when one is missing | [D14](#d14) |

## Details

### D1

**BLOCKER P2.** `proxy_pass` uses a resolver and a variable.

With a literal host, nginx resolves the name once per worker. Recreating `backend`, `cib7`, `mcp` or
`mobile` gives the container a new IP, and every proxied call answers 502 while `docker compose ps`
shows everything up. A literal host also stops nginx from starting while a name does not resolve yet.
The fix, now in both `frontend/nginx.conf` and `mobile/nginx.conf`:

```nginx
resolver 127.0.0.11 valid=10s ipv6=off;
set $backend_upstream http://backend:8085;
location /api/ { proxy_pass $backend_upstream; }
```

The resolver alone does nothing; the variable forces per-request resolution. Do not "simplify" it back.

*From roppoc.*

### D2

**BLOCKER P2.** Caching and the SPA fallback.

`index.html` is the one unhashed file that names the hashed bundles. Without `Cache-Control`, browsers
apply heuristic freshness and keep an old copy after a deploy, asking for bundles the new image no
longer has. Worse, with only `try_files $uri $uri/ /index.html`, a missing `.js` is answered with
`index.html` as a 200; the browser fails to run it as a module and shows the previous build with
nothing logged. `frontend/nginx.conf` now has:

- `location = /index.html` with `no-store` (every SPA route ends there through the fallback, so the
  MCP discovery headers are repeated there);
- `location = /env.js` with `no-store`, because it is written at container start;
- `location = /theme-init.js` with `no-cache`;
- `location ^~ /assets/` with a one-year immutable `Cache-Control` sent only on success, and
  `try_files $uri =404`.

`mobile/nginx.conf` serves a Flutter build whose `main.dart.js` is not hashed; its caching has not been
reviewed against this rule yet.

*From roppoc.*

### D7

**BLOCKER P1.** A published port is a configurable binding or profile-gated.

"Not routed by the ingress" is not "not reachable". In roppoc one `ports:` entry without a bind prefix
put the document store on the public interface of a VM where everything else was loopback. In the
deploy kit, `FRONTEND_HTTP_PORT` and `MOBILE_HTTP_PORT` take an `ip:port` binding, and Mailpit's UI
sits behind the `mail` profile. A new published port in `deploy/docker-compose.yml` follows one of
those two patterns.

*From roppoc.*

### D11

**BLOCKER P2.** Brute-force protection without permanent lockout; a password policy fits the seeded users.

`bruteForceProtected` is on and `permanentLockout` is unset (false). Keep it false: permanent lockout
lets anyone lock a named user out with a few wrong guesses. Before adding a `passwordPolicy`, change
the seeded users' passwords (`bart`/`bart` fails any sensible policy): the import enforces the policy
and Keycloak then refuses to boot.

*From roppoc.*

### D12

**BLOCKER P2.** Compose merges `ports` by concatenation.

An overlay adding `127.0.0.1:3000:8080` leaves the base file's `3000:8080` in place, so the port stays
open on every interface. Express bindings in the base file, with a variable.

*From roppoc.*

### D14

**WARNING.** A workflow forwards every secret it needs and fails when one is missing.

A repository secret the deploy workflow never passes to the host gives a green deploy and a broken
feature with no log. The deploy step lists the secrets it forwards and aborts on an empty one, as
`deploy.sh` does for the dev defaults.

*From roppoc.*
