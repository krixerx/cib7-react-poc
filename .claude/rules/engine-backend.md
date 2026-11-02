---
paths:
  - "cib7/**"
  - "backend/**"
---

# cib7/ and backend/

Load `backend-conventions` before changing code here. Owner: `programmer-backend`.

`cib7/` is engine and plugins only: no business endpoints, no service files. `backend/` owns every `/api/**` path and reaches the engine only through `/engine-rest`. Read `docs/security.md` before touching auth, endpoints or variables. Run Maven from the repo root so `.mvn/jvm.config` applies: `mvn -B -f <module>/pom.xml verify`.
