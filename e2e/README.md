# End-to-end tests

Playwright tests that drive a real Chromium against a deployed stack: the SPA,
the Keycloak login and the services behind them. By default they target the
public VM at `https://companylab.ai`.

```bash
cd e2e
npm install
npx playwright install chromium   # once per machine
npm test                          # headless
npm run test:headed               # watch the browser
npm run report                    # open the last HTML report
```

Environment variables:

| Variable | Default | Purpose |
| --- | --- | --- |
| `E2E_BASE_URL` | `https://companylab.ai` | Deployment under test |
| `E2E_APPLICANT_USER` | `bart` | Applicant login |
| `E2E_APPLICANT_PASSWORD` | `bart` | Applicant password |

Tests run against a shared deployment, so a test that starts a case leaves it
there until the engine restarts (process state is in-memory H2). On failure,
`test-results/` keeps a screenshot and a trace (`npx playwright show-trace
<path>/trace.zip`).
