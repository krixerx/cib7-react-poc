---
paths:
  - "frontend/src/**"
  - "frontend/index.html"
  - "frontend/public/**"
---

# frontend/

Load `frontend-conventions` before changing code here. Owner: `programmer-frontend`; UX decisions belong to `designer`.

After a TypeScript change, run `npm run typecheck` first, as the cheapest check. Full gate: `npm run format:check && npm run lint && npm run typecheck && npm test -- --run && npm run build`.
