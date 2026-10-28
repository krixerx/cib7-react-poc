---
name: frontend-conventions
description: Rules for writing, changing or reviewing the React SPA in frontend/: tokens and RTL-safe CSS, texts in en and ar, form rendering, state across conditional rendering, routes, XSS sinks, secrets, fetch error handling and spec hygiene. Use before changing a page, component, form renderer, stylesheet, route, API client or test, and when running /review.
---

# Frontend conventions

The rule table for changes in `frontend/`. Where a rule is already in `CLAUDE.md`, the table points
there. Rules with a Details section are lessons from a sibling project (roppoc) and the team's
TypeScript review manifest that this repository had not recorded yet. nginx, CSP and caching rules
are in `deployment-conventions`.

Severity: **BLOCKER P1** is a security or data-integrity fault, **BLOCKER P2** a fault that breaks
behaviour, **BLOCKER P3** a quality fault; all three block a commit. **WARNING** needs a stated reason
to skip. **INFO** is advice. `/review` uses these levels.

## Rules

| ID | Severity | Rule | Where |
|---|---|---|---|
| F1 | BLOCKER P1 | No raw HTML from data: no `dangerouslySetInnerHTML`, `innerHTML =`, `insertAdjacentHTML` | [F1](#f1) |
| F2 | BLOCKER P1 | No `eval`, `new Function(string)`, `document.write`, string `setTimeout`; no inline `<script>` | [F2](#f2) |
| F3 | BLOCKER P1 | No secrets in frontend source; `env.js` holds only public values | [F3](#f3) |
| F4 | BLOCKER P2 | Every colour is a token with a light and a dark value; generated forms keep the class contract | `CLAUDE.md` "Styling is plain CSS on tokens" |
| F5 | BLOCKER P2 | Logical CSS properties (`inset-inline-start`, `margin-inline-end`), never `left`/`right` | [F5](#f5) |
| F6 | BLOCKER P2 | Every text in both `en` and `ar`; service texts in the pack, core texts in the SPA | `CLAUDE.md` "Service texts live in the pack" |
| F7 | BLOCKER P2 | Every awaited API call has error handling that renders the server's message as text | [F7](#f7) |
| F8 | BLOCKER P2 | State that must outlive a conditionally rendered child is owned by the parent | [F8](#f8) |
| F9 | BLOCKER P2 | A component reads only route params its route declares | [F9](#f9) |
| F10 | BLOCKER P3 | No `any` in non-test code | [F10](#f10) |
| F11 | BLOCKER P3 | No focused or skipped tests committed | [F11](#f11) |
| F12 | WARNING | Tests restore `localStorage`, language and `dir` they change | [F12](#f12) |
| F13 | WARNING | `target="_blank"` links carry `rel="noopener noreferrer"` | [F13](#f13) |
| F14 | WARNING | Transitions name their properties; motion sits behind `prefers-reduced-motion` | [F14](#f14) |

## Details

### F1

**BLOCKER P1.** No raw HTML from data.

React escapes everything rendered as text. `dangerouslySetInnerHTML`, `element.innerHTML =`,
`outerHTML =` and `insertAdjacentHTML` bypass that, and this SPA shows applicants' and officers' own
text back to each other. Render text; if markup is truly needed, sanitise it with a vetted sanitiser
first and say why in a comment.

*From the team's TypeScript review manifest, R15.1 and R15.3.*

### F2

**BLOCKER P1.** No dynamic code and no inline script.

The CSP is `script-src 'self'`, so `eval`, `new Function(string)`, string `setTimeout`/`setInterval`
and inline `<script>` blocks fail at runtime in Docker while working in `npm run dev`. Every script
`index.html` loads is a file (`env.js`, `theme-init.js`, the bundle); keep it that way, including for
any Vite plugin that would inject an inline script.

*R15.2, plus roppoc's CSP lesson.*

### F3

**BLOCKER P1.** No secrets in frontend source.

Everything shipped to the browser is public, `env.js` included. It may hold URLs, the realm and the
public client id, never a client secret, an internal token or an API key.

**Detect:** string literals with `Bearer `, `apiKey`, `secret`, `password`, `-----BEGIN` in
`frontend/` source, `public/` or `docker/40-runtime-env.sh`.

*R15.4.*

### F5

**BLOCKER P2.** Logical CSS properties.

`ar` is right-to-left. `left`, `right`, `margin-left`, `padding-right` and `text-align: left` look
right in English and break in Arabic. The stylesheets currently use logical properties only; keep it
at zero.

*From roppoc.*

### F7

**BLOCKER P2.** Every awaited API call has error handling that renders the server's message.

The API clients throw typed errors (`ConsentError`, `DocumentsApiError`, the `CIB seven REST <status>`
error) that carry the status and body. A page catches them and renders the message as text
(`<p className="form-error">`, no toasts). An `await` with no `try`/`catch` on its path leaves a
button that silently does nothing; a `catch` that shows only a fixed string hides the one line that
says what failed.

*R17.2, plus roppoc's lesson.*

### F8

**BLOCKER P2.** State that must outlive a conditionally rendered child is owned by the parent.

A child rendered under `{step === 3 && <Uploader />}` is unmounted when the condition changes, and its
`useState` goes with it. Data later steps need (staged files, entered values) lives in the parent and
is passed down with a setter. In roppoc, uploads the citizen had watched succeed were silently dropped
at submit because the uploader held the list itself.

*From roppoc, translated from Angular `@if` to React conditional rendering.*

### F9

**BLOCKER P2.** A component reads only the route params its route declares.

`useParams()` returns `undefined` for a param the matched path does not declare, and the page then
calls `/engine-rest/task//form`. When one value needs a different component, keep the path
parameterised and branch inside the element, not with a literal segment.

*From roppoc.*

### F10

**BLOCKER P3.** No `any` in non-test code.

Use `unknown` and narrow, or declare the type. Where a library hands back untyped data, keep one typed
adapter with a comment saying why.

*R10.3.*

### F11

**BLOCKER P3.** No focused or skipped tests.

`it.only`, `describe.only`, `it.skip`, `describe.skip`, `test.only` make CI run a subset and report
green, or drop a test silently. Applies to `frontend/`, `mcp/` and `e2e/`.

*R18.4.*

### F12

**WARNING.** Tests restore shared browser state.

i18next caches the language in `localStorage`, and the colour scheme and task-list collapse are
stored there too. A test that changes them, or `document.documentElement.dir`, restores them in
`afterEach`. Vitest isolates files by default here; if `isolate: false` or a shared pool is ever
configured, a leak will fail unrelated files on machines with fewer cores, as it did in roppoc.

*R18.5, from roppoc.*

### F13

**WARNING.** External `target="_blank"` links carry `rel="noopener noreferrer"`.

*R15.9.*

### F14

**WARNING.** Transitions name their properties; motion sits behind `prefers-reduced-motion`.

`transition: all` also animates layout on hover. Wrap animations in
`@media (prefers-reduced-motion: no-preference)` or switch them off under `reduce`.

*From roppoc.*
