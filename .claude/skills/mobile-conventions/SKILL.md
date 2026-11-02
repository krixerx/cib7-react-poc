---
name: mobile-conventions
description: "Rules for writing, changing or reviewing the Flutter web applicant app in mobile/: PKCE auth, API calls, pack content, nginx and the Docker build. Use before changing mobile/lib, mobile/test, mobile/nginx.conf or mobile/Dockerfile, and when running /review."
---

# Mobile conventions

`mobile/` is a Flutter **web** app served under `/mobile/`. The platform folders are not committed;
`flutter create` runs inside the Docker build. Flutter is pinned in `mobile/Dockerfile`. No CI job
runs `flutter test` or `flutter analyze`, so run them yourself (`mobile/README.md` has the scaffold
step). The nginx rules are in `deployment-conventions` D1, D2 and D5.

Severity: **BLOCKER P1** is a security or data-integrity fault, **BLOCKER P2** a fault that breaks
behaviour, **BLOCKER P3** a quality fault; all three block a commit. **WARNING** needs a stated reason
to skip. **INFO** is advice. `/review` uses these levels.

## Rules

| ID | Severity | Rule |
|---|---|---|
| MOB1 | BLOCKER P1 | Auth is PKCE S256 with client `cib7-mobile`; configuration comes from `window.__ENV__`, and no secret ships in the app |
| MOB2 | BLOCKER P1 | `/api/internal` answers 404 at this nginx too |
| MOB3 | BLOCKER P2 | The API is same-origin by default, with `ENGINE_BASE` as the only override; every call carries the Bearer through the token provider |
| MOB4 | BLOCKER P2 | Pack content comes from `/mobile/pack/` through `lib/pack.dart`, which never throws; do not name a pack's service in `lib/` |
| MOB5 | WARNING | The app is English only, unlike the SPA; a new language is a design decision, not a string change |
