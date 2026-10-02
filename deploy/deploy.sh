#!/usr/bin/env bash
# Update-in-place for the pull-only bundle. Run ON the deployment host, from
# anywhere:   /path/to/deploy/deploy.sh [--realm] [--yes] [--no-backup] [--no-git] [--profile <p>]...
#             /path/to/deploy/deploy.sh --check [--host-dir <dir>]
#
# --check only runs the host checks below and changes nothing. --host-dir
# reads the host's own files (.env, traefik/dynamic/routes.yml) from <dir>
# while the reference files (docker-compose.yml, routes.yml.example) stay the
# ones beside this script: the deploy workflow's preflight runs the commit's
# copy of this script from a temp dir against the live bundle, before
# anything is shipped.
#
# Host checks (every run; a deploy refuses to start while one fails):
#   * routes.yml does not point at the old frontend:80 / mobile:80 and
#     defines every middleware routes.yml.example defines; with the tls
#     profile active, routes.yml must exist at all.
#   * On a public deployment (PUBLIC_FRONTEND_URL set and not localhost),
#     every secret docker-compose.yml gives a dev default must be set in .env
#     to something other than that default. Otherwise compose silently runs
#     on the published value. Only variable names are ever printed.
#
# What it does, in order:
#   1. git pull — only when the bundle lives in a git clone and --no-git was
#      not passed. It refuses to proceed if upstream changed a file you hold
#      skip-worktree'd per-host edits on; those need a hand-merge first.
#      The GitHub Actions deploy passes --no-git: it ships these files over
#      ssh itself, from the commit being deployed, so there is nothing to pull.
#   2. tars the rustfs volume (documents/PDFs — the only persistent data)
#      into this directory. Skip with --no-backup.
#   3. docker compose pull + up -d. If the engine image changed this
#      recreates the engine and WIPES running process instances (in-memory
#      H2) — the script asks first unless --yes.
#   4. --realm additionally recreates Keycloak so an edited
#      keycloak/realm-export.json is re-imported (one-shot import; this also
#      drops users registered at runtime).
#   5. Smoke-tests the public endpoints (PUBLIC_FRONTEND_URL from .env).
#
# Profiles: pass --profile tls (repeatable), or better, set
# COMPOSE_PROFILES=tls once in .env — docker compose picks it up on every
# command and the flag becomes unnecessary.
set -euo pipefail
cd "$(dirname "$0")"

PROFILES=()
DO_GIT=1
DO_REALM=0
ASSUME_YES=0
DO_BACKUP=1
CHECK_ONLY=0
HOST_DIR=$PWD
while [ $# -gt 0 ]; do
  case "$1" in
    --profile)   PROFILES+=(--profile "$2"); shift 2 ;;
    --realm)     DO_REALM=1; shift ;;
    --yes|-y)    ASSUME_YES=1; shift ;;
    --no-backup) DO_BACKUP=0; shift ;;
    --no-git)    DO_GIT=0; shift ;;
    --check)     CHECK_ONLY=1; shift ;;
    --host-dir)  HOST_DIR=$(cd "$2" && pwd); shift 2 ;;
    *) echo "usage: deploy.sh [--realm] [--yes] [--no-backup] [--no-git] [--profile <p>]" >&2
       echo "       deploy.sh --check [--host-dir <dir>]" >&2; exit 2 ;;
  esac
done
if [ "$HOST_DIR" != "$PWD" ] && [ "$CHECK_ONLY" != 1 ]; then
  echo "--host-dir is only for --check: a deploy always runs on the bundle it sits in" >&2
  exit 2
fi

say() { printf '\n==> %s\n' "$*"; }

# env_get <VAR>: the value docker compose would substitute, i.e. the process
# environment first, then the last assignment in the host's .env. Prints
# nothing when the variable is unset or empty.
env_get() {
  if [ -n "${!1:-}" ]; then printf '%s' "${!1}"; return; fi
  local line
  line=$(grep -E "^[[:space:]]*(export[[:space:]]+)?$1=" "$HOST_DIR/.env" 2>/dev/null | tail -1) || true
  line=${line#*=}
  line=${line%$'\r'}
  case "$line" in
    \"*\") line=${line#\"}; line=${line%\"} ;;
    \'*\') line=${line#\'}; line=${line%\'} ;;
  esac
  printf '%s' "$line"
}

# host_checks: prints one line per finding and returns non-zero if any failed.
host_checks() {
  local bad=0 routes="$HOST_DIR/traefik/dynamic/routes.yml"
  local example="traefik/dynamic/routes.yml.example"

  # --- routes.yml ---
  local profiles
  profiles="$(env_get COMPOSE_PROFILES) ${PROFILES[*]:-}"
  if [ ! -f "$routes" ]; then
    case ",$profiles," in
      *tls*) echo "FAIL  routes.yml missing while the tls profile is on: Traefik would route nothing."
             echo "      cp $example $routes and add your Host() rules"; bad=1 ;;
      *)     echo "ok    no routes.yml (tls profile off)" ;;
    esac
  else
    # The frontend and mobile images run nginx unprivileged on 8080 (they used
    # to listen on 80). A routes.yml copied from the old example leaves every
    # TLS route answering 502 while the localhost smoke tests still pass.
    if grep -qE 'https?://(frontend|mobile):80"' "$routes"; then
      echo "FAIL  routes.yml still points at frontend:80 / mobile:80; both listen on 8080 now"
      bad=1
    else
      echo "ok    routes.yml backend ports"
    fi
    # A middleware the example defines but routes.yml lacks means the example
    # was changed (security headers, rate limits) and not merged into the host.
    local mw missing=""
    for mw in $(awk '/^  middlewares:/{m=1;next} /^  [^ ]/{m=0}
                     m && /^    [A-Za-z0-9_-]+:[[:space:]]*$/{sub(/^ +/,"");sub(/:.*/,"");print}' "$example"); do
      grep -qE "^[[:space:]]+$mw:[[:space:]]*$" "$routes" || missing="$missing $mw"
    done
    if [ -n "$missing" ]; then
      echo "FAIL  routes.yml lacks middleware(s) from routes.yml.example:$missing"
      echo "      merge the example's middlewares section and add them to each router"
      bad=1
    else
      echo "ok    routes.yml middlewares"
    fi
  fi

  # --- secrets ---
  local public
  public=$(env_get PUBLIC_FRONTEND_URL)
  case "$public" in
    ""|*://localhost*|*://127.0.0.1*)
      echo "ok    secrets not enforced: PUBLIC_FRONTEND_URL is '${public:-unset}', a local run"
      return "$bad" ;;
  esac
  # Read the candidates out of the compose file the host will run, so a new
  # secret is covered without anyone remembering to list it here: every
  # ${VAR:-default} whose default says change-me, plus every *_SECRET.
  local m var def seen=" " value unset="" defaulted=""
  while IFS= read -r m; do
    var=${m#\$\{}; var=${var%%:-*}
    def=${m#*:-}; def=${def%\}}
    case "$def" in
      *change-me*) ;;
      *) case "$var" in *_SECRET) ;; *) continue ;; esac ;;
    esac
    case "$seen" in *" $var "*) continue ;; esac
    seen="$seen$var "
    value=$(env_get "$var")
    if [ -z "$value" ]; then unset="$unset $var"
    elif [ "$value" = "$def" ]; then defaulted="$defaulted $var"
    fi
  done < <(grep -oE '\$\{[A-Z0-9_]+:-[^}]*\}' docker-compose.yml)
  if [ -n "$unset$defaulted" ]; then
    [ -n "$unset" ] && echo "FAIL  not set in .env:$unset"
    [ -n "$defaulted" ] && echo "FAIL  still the committed dev default:$defaulted"
    echo "      compose would run $public on secrets published in the repo; set each"
    echo "      to a fresh value, e.g. openssl rand -hex 32"
    bad=1
  else
    echo "ok    secrets ($(echo $seen | wc -w) checked, none default)"
  fi
  return "$bad"
}

if [ "$CHECK_ONLY" = 1 ]; then
  say "host checks against $HOST_DIR"
  if host_checks; then say "ready to deploy"; exit 0; fi
  echo >&2
  echo "NOT ready: fix the FAIL lines above on the host, then re-run." >&2
  exit 1
fi

# --- 1. refresh the bundle from git (when it is a clone, not a tarball) ----
# --no-git is for a bundle that arrives by other means — the GitHub Actions
# deploy ships these exact files over ssh before calling this script, so pulling
# here would either be a no-op or fight what CI just placed.
if [ "$DO_GIT" = 1 ] && git -C .. rev-parse --git-dir >/dev/null 2>&1; then
  say "git fetch"
  git -C .. fetch
  upstream=$(git -C .. rev-parse --abbrev-ref '@{upstream}' 2>/dev/null || echo "")
  if [ -z "$upstream" ]; then
    echo "current branch has no upstream — skipping git pull" >&2
  else
    # skip-worktree'd files carry per-host edits git won't merge for us.
    # If upstream touched one, stop and let a human reconcile.
    mapfile -t skipped < <(git -C .. ls-files -v | awk '$1=="S"{$1="";sub(/^ /,"");print}')
    if [ "${#skipped[@]}" -gt 0 ]; then
      clash=$(git -C .. diff --name-only "HEAD..$upstream" -- "${skipped[@]}")
      if [ -n "$clash" ]; then
        echo "REFUSING to pull: upstream changed file(s) you hold per-host edits on:" >&2
        echo "$clash" >&2
        echo "Hand-merge (git update-index --no-skip-worktree <file>, merge, re-mark), then re-run." >&2
        exit 1
      fi
    fi
    say "git merge --ff-only $upstream"
    git -C .. merge --ff-only "$upstream"
  fi
fi

# After the pull, so the checks read the reference files being deployed, and
# before the backup and the confirmation, so a host that is not ready is told
# so before anything is touched.
say "host checks"
if ! host_checks; then
  echo >&2
  echo "REFUSING to deploy: fix the FAIL lines above on the host, then re-run." >&2
  exit 1
fi

# --- 2. back up the one persistent piece -----------------------------------
if [ "$DO_BACKUP" = 1 ]; then
  vol=$(docker volume ls -q | grep -E '_rustfs-data$' | head -1 || true)
  if [ -n "$vol" ]; then
    stamp=$(date +%Y%m%d-%H%M%S)
    say "backing up $vol -> rustfs-backup-$stamp.tgz"
    docker run --rm -v "$vol":/data:ro -v "$PWD":/backup alpine:3.24\
      tar czf "/backup/rustfs-backup-$stamp.tgz" -C / data
  else
    echo "no rustfs-data volume found yet — nothing to back up"
  fi
fi

# --- 3. confirm the destructive part ----------------------------------------
if [ "$ASSUME_YES" != 1 ]; then
  echo
  echo "Pulling images may recreate the engine (running cases are LOST — in-memory H2)."
  [ "$DO_REALM" = 1 ] && echo "--realm recreates Keycloak (runtime-registered users are LOST)."
  printf 'Continue? [y/N] '
  read -r answer
  case "$answer" in y|Y|yes|YES) ;; *) echo "aborted"; exit 1 ;; esac
fi

say "docker compose pull"
docker compose "${PROFILES[@]}" pull

if [ "$DO_REALM" = 1 ]; then
  say "recreating keycloak (realm re-import)"
  docker compose "${PROFILES[@]}" rm -sf keycloak
fi

say "docker compose up -d"
docker compose "${PROFILES[@]}" up -d --remove-orphans

# --- 4. smoke tests ---------------------------------------------------------
base=$(grep -E '^PUBLIC_FRONTEND_URL=' .env 2>/dev/null | tail -1 | cut -d= -f2- || true)
base=${base:-http://localhost:3000}
say "waiting for the engine at $base (first boot takes a minute — it waits for Keycloak)"
#
# Probe GET /engine-rest/process-definition, not /engine-rest/engine. Only the
# former is anonymous (PublicEngineRestSecurityConfig, order 0, GET + that exact
# path); everything else under /engine-rest/** needs a Bearer JWT and answers an
# unauthenticated probe with 401, which `curl -f` reports as failure no matter
# how healthy the engine is. It is also the better readiness signal: a non-empty
# list proves ServiceDeployments finished deploying the BPMN, not merely that the
# port is open.
ok=0
for _ in $(seq 1 60); do
  if curl -fsS --max-time 5 "$base/engine-rest/process-definition" 2>/dev/null | grep -q businessRegistration; then
    ok=1; break
  fi
  sleep 5
done
[ "$ok" = 1 ] || { echo "FAIL: engine did not come up — docker compose logs cib7" >&2; exit 1; }

fail=0
check() { # check <label> <url> [<expected-substring>]
  body=$(curl -fsS --max-time 10 "$2" 2>&1) \
    && { [ -z "${3:-}" ] || printf '%s' "$body" | grep -q "$3"; } \
    && echo "PASS  $1" \
    || { echo "FAIL  $1  ($2)"; fail=1; }
}
check "engine          " "$base/engine-rest/process-definition" "businessRegistration"
check "backend API     " "$base/api/public/vehicle-registry/vehicles"
check "frontend        " "$base/"
check "mobile app      " "$base/mobile/"
check "MCP manifest    " "$base/.well-known/mcp.json" "mcp"

if [ "$fail" = 1 ]; then
  echo
  echo "Deploy finished but smoke tests FAILED — inspect: docker compose logs -f" >&2
  exit 1
fi
say "deploy OK — $(docker compose "${PROFILES[@]}" ps --format '{{.Service}} {{.Status}}' | wc -l) services up"
