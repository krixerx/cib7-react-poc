# CI/CD

**When to read this:** when a GitHub Actions run failed, when you want to
deploy the POC to its VM from GitHub instead of by hand, or when you are
setting that up for the first time. Pairs with
[`../deploy/README.md`](../deploy/README.md) (what the deployment bundle
is and how to operate the host) and [`deployment.md`](deployment.md)
(deploying from source, which CI does not do).

**Contents**
1. [The workflows](#the-workflows)
2. [The deploy, step by step](#the-deploy-step-by-step)
3. [What to configure](#what-to-configure)
4. [First-time setup](#first-time-setup)
5. [Reaching a VM the runners cannot](#reaching-a-vm-the-runners-cannot)
6. [Rolling back](#rolling-back)
7. [Gotchas](#gotchas)

---

## The workflows

| Workflow | Runs when | Does |
|---|---|---|
| [`quality.yml`](../.github/workflows/quality.yml) | every PR; on `main` as the first job of `docker-publish.yml` (reusable workflow) | `mvn verify` for `cib7` + `backend` (Spotless included), format/lint/typecheck/test/build for `frontend` and `mcp`, and packaging the vendorable service-builder (`scripts/package-service-builder.mjs`, which fails on a link that would be dead in a pack repository). The same commands as the ones in `CLAUDE.md`, so a green local run means a green CI run. |
| [`docker-publish.yml`](../.github/workflows/docker-publish.yml) | every push to `main`, a `v<x.y.z>` tag, or by hand | Runs `quality.yml`, and only if it passes builds the seven core images (`krixerx/cib7-poc-*-core` for frontend, mobile, cib7, backend and mcp, plus `cib7-poc-pdf-renderer` and `cib7-poc-esb`, which hold nothing of a pack), scans each with Trivy (fails on a fixable HIGH or CRITICAL vulnerability, before anything is pushed) and pushes them. A second job then builds the reference pack's layers (`packs/reference/docker/*.Dockerfile`) on the core images of the same commit, scans and pushes them under the names the deploy bundle pulls (`cib7-poc-frontend`, `-cib7`, `-backend`, `-mobile`, `-mcp`). Every image is tagged `latest` and the commit's 7-character SHA; a `v<x.y.z>` tag also tags it `<x.y.z>`, the core version a pack's layers name in their `*_CORE` build argument. |
| [`release.yml`](../.github/workflows/release.yml) | a `v<x.y.z>` tag | Packages the service-builder skill for that core release and attaches `service-builder-<x.y.z>.tar.gz` to the tag's GitHub release (creating the release if needed). A pack repository unpacks it into `.claude/skills/service-builder/`; its `VERSION` names the core release and the platform API. |
| [`security.yml`](../.github/workflows/security.yml) | every PR, every push to `main`, Mondays 05:17 UTC | `npm audit --audit-level=high` for `frontend`, `mcp`, `scripts` and `pdf-renderer`, and a Trivy filesystem scan (Maven poms, npm locks, committed secrets; HIGH and CRITICAL with a fix). Not a publish gate, so a new advisory turns it red without blocking unrelated merges. |
| [`codeql.yml`](../.github/workflows/codeql.yml) | **only when you ask** (`workflow_dispatch`) | CodeQL static analysis with the `security-extended` queries for `java-kotlin` (`cib7`, `backend`), `javascript-typescript` (`frontend`, `mcp`, `pdf-renderer`, `scripts`) and `actions`, all in build-mode none. Findings go to Security > Code scanning. Fails only if the analysis itself fails, not on findings. |
| [`deploy.yml`](../.github/workflows/deploy.yml) | **only when you ask** (`workflow_dispatch`) | Ships the `deploy/` bundle to the VM over SSH and runs the host's `deploy.sh`. Builds nothing. |

[`dependabot.yml`](../.github/dependabot.yml) opens weekly update PRs for
Maven (`cib7`, `backend`), npm (`frontend`, `mcp`, `scripts`,
`pdf-renderer`), the Dockerfiles' base images, the images pinned in both
compose files, and the GitHub Actions themselves. Minor and patch bumps come
grouped; majors one at a time. MUI majors, Node majors and Java majors are
ignored on purpose (see the comments in the file).

Accepted image-scan findings go in a per-image Trivy YAML ignore file that
`docker-publish.yml` passes to that image's scan only (the matrix's
`trivyignores` field). Each entry names the CVE, the path it sits in, a
`statement` saying why, and an `expired_at` date, so the build turns red
again when the exception lapses. An image without a file is scanned with no
exceptions. There are two:

- `esb/.trivyignore.yaml`: Jackson and FreeMarker in Camel JBang's
  dependency cache and libraries inside `jbang.jar` (jsoup among them),
  which our Dockerfile cannot upgrade. Expires 2026-11-13.
- `cib7/.trivyignore.yaml`: Spring MVC's XsltView RCE (CVE-2026-47884). The
  engine uses no XsltView, the Spring 6.2 line has no fixed release, and the
  fix in Spring 7 needs the engine on Spring Boot 4. `security.yml` passes the
  same file to the filesystem scan, where the finding sits in
  `cib7/pom.xml`. Expires 2026-11-05.

The deploy is manual on purpose: a deploy restarts the engine and can run
schema migrations against the live Postgres data, which is not something a
merge should decide.

## The deploy, step by step

Dispatch it from **Actions → Deploy to VM → Run workflow**, or:

```bash
gh workflow run deploy.yml --ref main
gh run watch                       # follow it
```

Inputs, all optional:

| Input | Default | Meaning |
|---|---|---|
| `image_tag` | the 7-char SHA of the dispatched ref | Which published tag to run. Name an older one to roll back. |
| `ship_realm` | off | Also overwrite `keycloak/cib7-poc-realm.json` on the host. Off by default because the host's copy holds that deployment's real client secrets. |
| `recreate_keycloak` | off | Recreate Keycloak so an edited realm is re-imported. Drops every session and every runtime-registered user. |
| `skip_backup` | off | Skip the rustfs volume backup (uploaded documents, generated PDFs). |
| `preflight_only` | off | Run only the reachability and host-readiness checks, deploy nothing. |

What the run does:

1. **Preflight.** Prints the runner's egress IP, resolves the host,
   checks TCP 22, then logs in and reports the Docker version, the
   deploy directory and whether a `.env` is there. Then it runs this
   commit's `deploy.sh --check` from a temp dir against the live bundle
   (see below), so a host whose `routes.yml` or `.env` is not ready for
   this commit fails here, before the bundle is shipped. Always runs; a
   deploy never starts against a host it cannot reach or is not ready.
2. **Verifies the images exist** under the tag it is about to deploy. The
   image names are read out of `deploy/docker-compose.yml` itself, so
   this check cannot drift from what compose will pull, and a tag nobody
   built fails the run in ten seconds instead of half-way through.
3. **Ships the bundle** — every git-tracked file under `deploy/`, as a
   tar over SSH. Because the list is `git ls-files`, a new tracked file
   ships without anyone remembering to add it here, and every per-host
   file is gitignored, so it cannot be clobbered by construction.
4. **Pins `IMAGE_TAG`** in the host's `.env`. The only line of `.env`
   this workflow ever writes, and it is about the *next* command someone
   types on the host: without it, a plain `docker compose up -d` there
   would silently move the whole stack to `latest`.
5. **Runs `deploy.sh --yes --no-git`** on the host: volume backup,
   `compose pull`, `up -d --remove-orphans`, then the public smoke tests.
   Those tests run on the host deliberately — the runner's only route in
   is port 22, so probing from the runner would fail a good deploy.
6. **Asserts the running images carry this commit**, by the
   `org.opencontainers.image.revision` label rather than the image ID (an
   ID is the config digest in the classic image store and the manifest
   digest in the containerd one, so comparing IDs fails a perfectly good
   deploy). Skipped when you named `image_tag` by hand, since that tag
   belongs to some other commit.
7. On failure, dumps `docker compose ps -a` and the last 80 log lines
   from the host into the run log.

**Never touched on the host:** `.env` (beyond that one `IMAGE_TAG` line),
`traefik/dynamic/*.yml`, `traefik/certs/*`, `traefik/acme/`,
`docker-compose.override.yml`, and `keycloak/cib7-poc-realm.json` unless
you pass `ship_realm`. Hostnames, client secrets and certificates stay
the host's own state; no deployment secret has to be stored in GitHub.

## What to configure

Repository **variables** (Settings → Secrets and variables → Actions →
Variables) — these are not secret, but the workflow also accepts each as
a secret if you prefer:

| Name | Example | Notes |
|---|---|---|
| `VM_HOST` | `poc.example.com` | Hostname or IP of the VM. |
| `VM_USER` | `deploy` | SSH login. Must be in the `docker` group. |
| `VM_DEPLOY_DIR` | `/opt/cib7-poc/deploy` | Optional; that value is the default. |

Repository **secrets**:

| Name | How to get it |
|---|---|
| `VM_SSH_PRIVATE_KEY` | The private half of a key whose public half is in `~/$VM_USER/.ssh/authorized_keys` on the VM. Paste it whole, or as one base64 line — the workflow accepts either and strips CRs. |
| `VM_KNOWN_HOSTS` | `ssh-keyscan <VM_HOST>` (without `-H`, so each line names the host in clear and can be checked). The first field must be exactly the `VM_HOST` value, or a comma-separated list containing it — a pin written for the hostname does not match when `VM_HOST` is the IP. **Required**: with it empty, every job that would log in fails before connecting (there is no trust-on-first-use). That failing run prints the scanned lines and their fingerprints for you; check the fingerprints against the VM before pasting them. |
| `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN` | Already set for `docker-publish.yml`. The deploy uses them only so its tag check does not spend the shared runner's anonymous pull quota. |

The deploy job declares `environment: vm`, so GitHub records a
deployment per commit. Add a required reviewer or a wait timer under
Settings → Environments → vm if you want approval before each deploy;
the workflow needs no change for that. You can also set the environment's
URL there so the run links to the running app.

## First-time setup

```bash
# 1. On your machine: a key for CI alone, no passphrase (nothing can type one).
ssh-keygen -t ed25519 -f ~/.ssh/cib7_deploy -C "github-actions deploy" -N ""

# 2. Authorise it on the VM, as the login the deploy will use.
ssh-copy-id -i ~/.ssh/cib7_deploy.pub deploy@poc.example.com

# 3. The VM login must be able to drive Docker without a password prompt.
ssh deploy@poc.example.com 'id -Gn; docker compose version'

# 4. Paste the PRIVATE key into the VM_SSH_PRIVATE_KEY secret.
cat ~/.ssh/cib7_deploy
```

Then pin the host key. Either run `ssh-keyscan <VM_HOST>` yourself, or run
the workflow with `preflight_only=true`: with `VM_KNOWN_HOSTS` empty it
prints the scanned lines and their fingerprints, then fails on purpose. In
both cases compare the fingerprints with the VM's own
(`ssh-keygen -lf /etc/ssh/ssh_host_ed25519_key.pub`, run on the VM) before
pasting the lines into the secret, then run the workflow again.

**If the key is rejected.** The secret is accepted raw, with stray blank
lines around it, or as one base64 line
(`base64 -w0 ~/.ssh/cib7_deploy`, the shape that survives any terminal
and clipboard). Three things get pasted by mistake, and the run names
each rather than failing as "Permission denied" later: the **public**
half (a `.pub` file — that one belongs in `authorized_keys` on the VM), a
PuTTY **`.ppk`** (PuTTYgen → Conversions → Export OpenSSH key, then paste
that file), and a **passphrase-protected** key, which cannot work because
nothing in CI can type the passphrase.

On a host that has never been deployed to, the first run also ships
`keycloak/cib7-poc-realm.json` — it has to, because compose bind-mounts
that path and Docker would otherwise create a *directory* there and
Keycloak would fail on something that reads nothing like "the realm is
missing". That copy carries this repository's **published dev client
secrets**. Replace them on the host, put the same values in `.env`, and
re-deploy with `recreate_keycloak=true` before anyone else can reach the
box. See [`../deploy/README.md`](../deploy/README.md#deploying-with-real-hostnames--tls).

**What this key can do.** Membership in the `docker` group is
root-equivalent on the target (`docker run -v /:/host` is all it takes),
so this is a root credential for that VM held in GitHub. Acceptable for a
POC host that holds no real data; for anything else, use a self-hosted
runner or a tailnet (next section) so the credential never has to be
reachable from the public internet.

## Reaching a VM the runners cannot

If preflight reports `22 BLOCKED`, GitHub's hosted runners cannot open
SSH to that host. Three ways out, best first:

1. **Self-hosted runner on the VM.** Install the runner on the host
   itself, change `runs-on: ubuntu-latest` to `runs-on: self-hosted` in
   both jobs of `deploy.yml`, and set `VM_HOST=127.0.0.1` with the
   runner's own user in `authorized_keys`. No firewall change, and no
   root credential leaves the machine.
2. **A tailnet or VPN.** Add a
   [`tailscale/github-action`](https://github.com/tailscale/github-action)
   step (with an OAuth client and a tagged ACL) before *Prepare SSH*, and
   point `VM_HOST` at the tailnet name. The host keeps port 22 closed to
   the internet.
3. **Allowlist GitHub's egress ranges.** `curl -s
   https://api.github.com/meta | jq -r '.actions[]'` lists them — several
   hundred CIDRs that change over time, and they are shared with every
   other GitHub customer. Workable, weakest of the three.

## Rolling back

Deploying an older build is a normal deploy with an explicit tag:

```bash
gh workflow run deploy.yml --ref main -f image_tag=04ea5f4
```

The images are immutable per commit, so this puts the exact previous
build back. Two consequences worth knowing before you do it: the
revision assertion is skipped (that tag is not this ref's commit), and
recreating the engine means the cases running right now are gone — see
below.

## Gotchas

- **A deploy can migrate the live database.** Cases survive a deploy in
  Postgres, but the engine and backend run their pending Flyway migrations
  when the new image starts, and a CIB seven upgrade migrates the `ACT_*`
  tables. Take a `pg_dump` first (docs/deployment.md, Day-2 operations).
  The first deploy that brings Postgres starts with empty databases: the
  cases that were in the old in-memory H2 are gone.
- **`deploy.sh` runs with `--yes` here**, so the confirmation you get
  interactively on the host is not in the loop. The dispatch is the
  confirmation.
- **The realm import is one-shot.** Shipping an edited
  `cib7-poc-realm.json` changes nothing until Keycloak is recreated; that
  is what `recreate_keycloak` is for, and it drops sessions and
  runtime-registered users with it.
- **Uploaded documents survive**, in the `rustfs-data` volume, and
  `deploy.sh` tars it into the deploy directory before touching anything
  unless you pass `skip_backup`. Those tarballs accumulate on the host —
  prune them.
- **First boot is slow.** The engine waits for Keycloak to become
  healthy; `deploy.sh` allows five minutes before calling it a failure.
- **No image without green quality gates.** `docker-publish.yml` calls
  `quality.yml` first and builds nothing if it fails, so every published
  tag passed the tests and the Trivy image scan. A red publish run therefore
  means `deploy.yml` will refuse that commit's tag as missing.
- **Inputs never reach a shell as text.** `deploy.yml` passes every
  `inputs.*` value to its scripts through `env:`, so a crafted tag or flag
  cannot finish someone else's shell line. Keep it that way when adding
  inputs.
- **The host's `.env` is edited in place without a backup copy**
  (`sed -i`, no `.bak`), and the run deletes a `.env.bak` that older runs
  left behind: a backup file would be a second, forgotten copy of every
  secret.
- **Host checks.** `traefik/dynamic/routes.yml` and `.env` are host-owned
  and never shipped, so they can fall behind the bundle. `deploy.sh`
  checks them before it touches anything, and `deploy.sh --check` runs
  only the checks. It fails when `routes.yml` still points at
  `frontend:80` or `mobile:80` (both listen on 8080 since the non-root
  images), lacks a middleware `routes.yml.example` defines, or is missing
  while the `tls` profile is on. On a public deployment
  (`PUBLIC_FRONTEND_URL` not localhost) it also fails when a secret that
  `deploy/docker-compose.yml` gives a dev default (`:-…change-me`, or any
  `*_SECRET`) is unset in `.env` or still that default: compose would
  otherwise run silently on a value published in this repo. The list is
  read from the compose file, so a new secret is covered automatically,
  and only variable names are printed.
