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

- tiles: started, completed, in progress, failed, cancelled;
- a status donut, stacked bars per service and per day;
- a task table: done, waiting now, average time;
- the open failures: time, service, case, task, error.

The period is a set of calendar days in the browser's own timezone (Today,
Yesterday, Last 7 days, Last 30 days, This month, or a custom range up to 366
days). The page refreshes every 5 minutes.

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
