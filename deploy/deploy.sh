#!/usr/bin/env bash
# Update-in-place for the pull-only bundle. Run ON the deployment host, from
# anywhere:   /path/to/deploy/deploy.sh [--realm] [--yes] [--no-backup] [--profile <p>]...
#
# What it does, in order:
#   1. git pull (only when the bundle lives in a git clone) — but refuses to
#      proceed if upstream changed a file you hold skip-worktree'd per-host
#      edits on; those need a hand-merge first.
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
DO_REALM=0
ASSUME_YES=0
DO_BACKUP=1
while [ $# -gt 0 ]; do
  case "$1" in
    --profile)   PROFILES+=(--profile "$2"); shift 2 ;;
    --realm)     DO_REALM=1; shift ;;
    --yes|-y)    ASSUME_YES=1; shift ;;
    --no-backup) DO_BACKUP=0; shift ;;
    *) echo "usage: deploy.sh [--realm] [--yes] [--no-backup] [--profile <p>]" >&2; exit 2 ;;
  esac
done

say() { printf '\n==> %s\n' "$*"; }

# --- 1. refresh the bundle from git (when it is a clone, not a tarball) ----
if git -C .. rev-parse --git-dir >/dev/null 2>&1; then
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

# --- 2. back up the one persistent piece -----------------------------------
if [ "$DO_BACKUP" = 1 ]; then
  vol=$(docker volume ls -q | grep -E '_rustfs-data$' | head -1 || true)
  if [ -n "$vol" ]; then
    stamp=$(date +%Y%m%d-%H%M%S)
    say "backing up $vol -> rustfs-backup-$stamp.tgz"
    docker run --rm -v "$vol":/data:ro -v "$PWD":/backup alpine \
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
ok=0
for _ in $(seq 1 60); do
  if curl -fsS --max-time 5 "$base/engine-rest/engine" 2>/dev/null | grep -q default; then
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
check "engine          " "$base/engine-rest/engine" "default"
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
