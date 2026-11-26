#!/bin/sh
# Creates the GELF input Graylog needs to accept this stack's logs.
#
# Graylog keeps inputs in MongoDB, not in a config file, so a fresh volume has
# no input listening on 12201 and every shipper would quietly write into a
# closed port. This script POSTs the input through the REST API once Graylog is
# up, and is safe to re-run: it matches on input title and skips what already
# exists.
#
# One input, GELF UDP 12201, for every shipper: cib7, backend (logback-gelf),
# mcp, pdf-renderer. There is no TCP input: the bus was its only user, and
# Patchbay logs to stdout only (docs/logging.md). A volume provisioned before
# keeps its old GELF TCP input until it is deleted in Graylog's UI.
#
# Runs as the `graylog-init` service in docker-compose.yml. Failures are loud:
# every API response is echoed, so `docker compose logs graylog-init` tells you
# exactly what Graylog rejected.
set -eu

GRAYLOG_API="${GRAYLOG_API:-http://graylog:9000/api}"
GRAYLOG_USER="${GRAYLOG_USER:-admin}"
GRAYLOG_PASSWORD="${GRAYLOG_PASSWORD:-admin}"

# Graylog needs this header on every mutating call as a CSRF guard; its value is
# only used for logging.
REQUESTED_BY="cib7-poc-graylog-init"

log() { echo "[graylog-init] $*"; }

# Graylog answers /system/lbstatus with 200 ALIVE only once the server has
# finished its migrations and index bootstrap. On a cold start with an empty
# OpenSearch volume that can take a couple of minutes.
log "waiting for Graylog at ${GRAYLOG_API} ..."
attempt=0
until curl -fsS -u "${GRAYLOG_USER}:${GRAYLOG_PASSWORD}" "${GRAYLOG_API}/system/lbstatus" >/dev/null 2>&1; do
  attempt=$((attempt + 1))
  if [ "${attempt}" -ge 120 ]; then
    log "ERROR: Graylog did not become ready within 10 minutes — giving up."
    log "       check 'docker compose logs graylog' (usually OpenSearch or MongoDB)."
    exit 1
  fi
  sleep 5
done
log "Graylog is up."

existing=$(curl -fsS -u "${GRAYLOG_USER}:${GRAYLOG_PASSWORD}" "${GRAYLOG_API}/system/inputs")

# create_input <title> <input class> <configuration json>
create_input() {
  title="$1"
  type="$2"
  configuration="$3"

  if echo "${existing}" | grep -q "\"title\":\"${title}\""; then
    log "input '${title}' already exists — skipping."
    return 0
  fi

  log "creating input '${title}' (${type}) ..."
  response=$(
    curl -sS -u "${GRAYLOG_USER}:${GRAYLOG_PASSWORD}" \
      -H 'Content-Type: application/json' \
      -H "X-Requested-By: ${REQUESTED_BY}" \
      -X POST "${GRAYLOG_API}/system/inputs" \
      -d "{\"title\":\"${title}\",\"type\":\"${type}\",\"global\":true,\"configuration\":${configuration}}"
  )
  log "  -> ${response}"
  # A successful create returns {"id":"..."}; anything else is a failure worth
  # failing the container on so it shows up as a non-zero exit in compose.
  echo "${response}" | grep -q '"id"' || {
    log "ERROR: creating '${title}' failed."
    return 1
  }
}

create_input 'GELF UDP' 'org.graylog2.inputs.gelf.udp.GELFUDPInput' '{
  "bind_address": "0.0.0.0",
  "port": 12201,
  "recv_buffer_size": 262144,
  "number_worker_threads": 2,
  "decompress_size_limit": 8388608
}'

log "done — input provisioned."
