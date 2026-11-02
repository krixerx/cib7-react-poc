---
name: pdf-renderer-conventions
description: Rules for changing the pdf-renderer service (Express facade over Gotenberg). Use before touching pdf-renderer/server.js, gelf.js or its Dockerfile, and when running /review.
---

# pdf-renderer conventions

A small Express facade: `POST /render {html, filename}` returns `{filename, base64}`, and
`GET /health` answers the healthcheck. The engine reaches it only across the bus. It has no tests,
so verify a change through a real render on the local stack.

Severity: **BLOCKER P1** is a security or data-integrity fault, **BLOCKER P2** a fault that breaks
behaviour, **BLOCKER P3** a quality fault; all three block a commit. **WARNING** needs a stated reason
to skip. **INFO** is advice. `/review` uses these levels.

## Rules

| ID | Severity | Rule |
|---|---|---|
| PDF1 | BLOCKER P1 | It has no auth of its own and relies on network isolation; never publish its port or add it to a wider network |
| PDF2 | BLOCKER P2 | Keep the response contract: the engine reads `base64` back through the bus |
| PDF3 | BLOCKER P2 | Missing `html` answers 400; a Gotenberg failure answers 502 |
| PDF4 | WARNING | The JSON body limit is 5 MB, and a larger document must change it deliberately |
| PDF5 | WARNING | GELF has a 7000-byte cap and trimming covers only `short_message`, so keep other fields short |
