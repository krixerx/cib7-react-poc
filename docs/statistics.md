# Process statistics

**When to read this:** before changing the statistics page, its endpoint or
who may see it; when the numbers on the page disagree with Cockpit; when
moving the stack to a database with real volume.

**Contents**
1. [What it shows](#what-it-shows)
2. [Who may see it](#who-may-see-it)
3. [How it is built](#how-it-is-built)
4. [Counting rules](#counting-rules)
5. [Limits](#limits)
6. [Later: reporting views](#later-reporting-views)

---

## What it shows

`/statistics` in the SPA answers "how are our services doing" for business
users who find Cockpit too technical. For the cases **started** in a period,
filtered by service and task, it shows:

- **Needs attention**, in the dark band at the top: failed cases, the
  back-office queue with the age of its oldest task, and any indicator that
  moved notably the wrong way against the previous period (5 percentage
  points for a rate, 25% for a count or time, only when both periods have at
  least 5 cases).
- **Indicators**, each with a sparkline and its change against the previous
  period of the same length: started, completed, average lead time, approval
  rate, returned for correction, without back office, failed. Choosing one
  shows it per day, with the previous period as a tick on each day.
- **Services**: stacked status bars, and a table of every indicator per
  service. Clicking a service focuses the page on it.
- **Case flow**: a Sankey from started, through "back office" or "no back
  office", to approved, rejected, in progress, failed or cancelled.
- **Back office**, as a team only: tasks done, waiting now, average task time
  (creation to completion, so queue time included), oldest waiting; then the
  per-task table. There are deliberately no per-person figures.
- **Open failures**: time, service, case, task, error.

The period is a set of calendar days in the browser's own timezone (Today,
Yesterday, 7 days, 30 days, This month, or a custom range up to 366 days).
The page makes two requests, one for the period and one for the period
before it, and refreshes every 5 minutes.

The design follows a "control room" dashboard mock-up, keeping only what the
engine history can back. Left out on purpose: forecasts and z-score baselines
(history is wiped on restart, so there is no baseline to trust), citizen
satisfaction, appeals, registry defects and SLA targets (no data source), and
the MCP-vs-portal share (the engine cannot yet tell an MCP start from a web
start; that needs a channel marker set server-side).

## Who may see it

Anyone with the Keycloak realm role **`statistics-viewer`**. The realm export
maps the role onto the `/civil-servant` group, so every PartB user has it. To
change who sees statistics, change it in Keycloak; nothing in the code checks
the PartB role.

- Give it to someone outside PartB: assign the role to that user directly.
- Take it from one civil servant: Keycloak has no deny rule, so while the role
  comes from the group you cannot. Remove the role from the group and assign
  it to users (or a `/statistics` group) instead.

Roles are read from the access token, so a change takes effect at the user's
next token refresh, at most one access-token lifetime later.

The check happens twice: `SecurityConfig` refuses `/api/statistics/**` without
the role (403, tested in `StatisticsAccessTest`), and the SPA only shows the
nav link and route when the token carries it (`canViewStatistics` in
`AuthProvider`). The backend check is the one that matters.

## How it is built

```
SPA /statistics ──GET /api/statistics?from&to&zone&service&task──▶ backend
backend ──(as cib7-business)──▶ /engine-rest history API ──▶ engine H2
```

- `StatisticsController` validates the filters and logs each request with
  them; `user_id` comes from the MDC, so Graylog records who looked.
- `StatisticsService` reads, as the `cib7-business` service account:
  latest process definitions, top-level historic process instances started in
  the range, historic activity instances started since the range start, and
  open incidents.
- `StatisticsAggregator` turns those rows into the report. It has no I/O and
  its rules are tested in `StatisticsAggregatorTest`.

The service account, not the caller's token, reads the engine. That way the
role alone decides access, even for a user with no engine grants of their own.
The endpoint returns aggregates and the open-failure list only, never process
variables. The failure list holds the business key and error text, which
civil servants already see on the Incidents page; widening the role beyond
PartB means those people see them too.

No new container, database user or engine change was needed. The draft
proposal (Grafana reading the history tables through a read-only database
user) assumed PostgreSQL. Here the engine runs in-memory H2 inside its JVM,
which no outside process can connect to.

## Counting rules

Every top-level case started in the range gets exactly one status, checked in
this order:

| Status | Rule |
|---|---|
| Completed | `state = COMPLETED` |
| Cancelled | `state = EXTERNALLY_TERMINATED` or `INTERNALLY_TERMINATED` |
| Failed | still running and has an open incident |
| In progress | still running, no open incident |

- A case that failed, was retried and then completed counts as completed.
- Call-activity sub-processes are not services of their own. Their tasks and
  incidents count under the top-level case through `rootProcessInstanceId`.
- An incident propagates a copy to every parent execution; the failure list
  shows only the root cause.
- "Tasks" are activities whose BPMN type ends in `Task` (user, service, send,
  receive, business-rule, script, manual). A task id is
  `<processDefinitionKey>:<activityId>` of the definition the task lives in.
- The task filter keeps cases that reached at least one selected task. The
  task list itself is computed before that filter, so picking a task does not
  hide the others.

Indicators beyond the status, all from activity rows, never from process
variables:

| Indicator | Rule |
|---|---|
| Approved / rejected | A completed case is rejected when its top-level definition ended on an end event whose id contains `Rejected` (`EndEvent_Rejected`), otherwise approved. A sub-process's end event does not count. |
| Approval rate | approved ÷ (approved + rejected) |
| Back-office task | A user task not assigned to the user who started the case (`startUserId`), including unclaimed ones. Applicant tasks are assigned to `${initiator}`. |
| Without back office | completed cases that never had a back-office task, ÷ completed |
| Returned for correction | cases in which the applicant did the same user task more than once, ÷ started |
| Average lead time | mean `durationInMillis` of the completed cases |

Vehicle registration and business registration have no rejected end event
today, so their approval rate is always 100%. A spec that adds a rejection
path should name its end event `EndEvent_Rejected` to be counted.

## Limits

- **History is wiped on restart.** Both Java modules run in-memory H2
  (`TODOS.md` T1), so the page only knows cases since the engine last started.
- **Row caps.** One request reads at most 10,000 cases and 100,000 activity
  rows; above that the page shows a warning and the numbers are incomplete.
  Activities are bounded only by the range start, so a long range on a busy
  engine reads every activity since then.
- **Freshness.** Up to 5 minutes old, or press Refresh.

## Later: reporting views

With PostgreSQL and real volume, aggregate in SQL instead of in the backend:
a `reporting` schema with views over `act_hi_procinst`, `act_hi_actinst`,
`act_hi_incident` and `act_re_procdef` that expose only the needed columns,
and a read-only database role with `SELECT` on those views only,
`default_transaction_read_only = on` and a `statement_timeout`. The endpoint,
the role check and the page stay as they are; only `StatisticsService`
changes. If Grafana is put on top of those views instead, the database role
is the security boundary: any Grafana viewer can send arbitrary SQL to its
data source.
