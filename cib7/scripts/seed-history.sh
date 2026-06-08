#!/bin/sh
# Pre-populates Bart's process-instance history on a cold `docker compose up`
# so the autofill demo (query_user_history) returns values from the first
# call. Runs as a one-shot compose service (`seed-history`) that exits 0
# once both seed instances are recorded.
#
# Why this exists: the engine uses in-memory H2 (TODOS.md T1 tracks the
# Postgres switch). Without a seed, the LLM has nothing to autofill from
# on a fresh deployment — the businessRegistration training markdown
# tells Claude to call query_user_history('applicantFirstName') and
# pre-fill, and an empty response forces "what is your name?" every demo.
#
# Auth: we ROPC as bart via the dedicated `cib7-seed` Keycloak client
# (password grant only, secret in env). bart's token starts and completes
# both seed instances; the engine records the variables in history exactly
# as a normal applicant run would.
#
# Idempotent: re-running just adds two more instances. query_user_history
# returns the most recent so behavior is unchanged. Restart the seed-history
# service after re-importing Keycloak or restarting cib7 (both wipe state).

set -eu

log() { echo "[seed-history] $*"; }

# ---------------------------------------------------------------------
# Wait for upstream services
# ---------------------------------------------------------------------

log "waiting for keycloak..."
i=0
until curl -sf "$KEYCLOAK_URL/realms/$KEYCLOAK_REALM/.well-known/openid-configuration" -o /dev/null 2>&1; do
    i=$((i+1))
    if [ $i -gt 60 ]; then
        log "FAILED: keycloak not reachable after 120s at $KEYCLOAK_URL"
        exit 1
    fi
    sleep 2
done
log "keycloak up"

log "waiting for engine..."
i=0
until curl -sf "$ENGINE_URL/engine-rest/process-definition" -o /dev/null 2>&1; do
    i=$((i+1))
    if [ $i -gt 60 ]; then
        log "FAILED: engine not reachable after 120s at $ENGINE_URL"
        exit 1
    fi
    sleep 2
done
log "engine up"

# Wait an extra beat so process-definition list (anonymous) is populated.
# Engine returns 200 with [] before deployments finish in some race windows.
i=0
while [ $i -lt 10 ]; do
    COUNT=$(curl -s "$ENGINE_URL/engine-rest/process-definition?latestVersion=true" | grep -o '"key"' | wc -l)
    if [ "$COUNT" -ge 2 ]; then
        break
    fi
    i=$((i+1))
    sleep 2
done
log "deployments visible ($COUNT process definition(s))"

# ---------------------------------------------------------------------
# Mint bart's token via the cib7-seed client (password grant)
# ---------------------------------------------------------------------

log "minting bart token via $KEYCLOAK_SEED_CLIENT_ID..."
TOKEN_RESPONSE=$(curl -s -X POST \
    "$KEYCLOAK_URL/realms/$KEYCLOAK_REALM/protocol/openid-connect/token" \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "client_id=$KEYCLOAK_SEED_CLIENT_ID" \
    -d "client_secret=$KEYCLOAK_SEED_CLIENT_SECRET" \
    -d "grant_type=password" \
    -d "username=$BART_USERNAME" \
    -d "password=$BART_PASSWORD" \
    -d "scope=openid")

TOKEN=$(echo "$TOKEN_RESPONSE" | jq -r '.access_token // empty')
if [ -z "$TOKEN" ]; then
    log "FAILED to mint token. Response:"
    echo "$TOKEN_RESPONSE"
    exit 1
fi
log "token minted (length=${#TOKEN})"

# ---------------------------------------------------------------------
# Helper: start a process + complete its first task
# ---------------------------------------------------------------------

start_and_complete() {
    DEF_KEY=$1
    START_VARS=$2
    TASK_VARS=$3

    log "$DEF_KEY: starting process..."
    INSTANCE=$(curl -s -X POST \
        "$ENGINE_URL/engine-rest/process-definition/key/$DEF_KEY/start" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"variables\":$START_VARS}")
    PROCESS_ID=$(echo "$INSTANCE" | jq -r '.id // empty')
    if [ -z "$PROCESS_ID" ]; then
        log "$DEF_KEY: FAILED to start. Response:"
        echo "$INSTANCE"
        return 1
    fi
    log "$DEF_KEY: started $PROCESS_ID"

    log "$DEF_KEY: looking up first task..."
    # The engine creates the task asynchronously after start; small retry.
    j=0
    while [ $j -lt 5 ]; do
        TASK_ID=$(curl -s -H "Authorization: Bearer $TOKEN" \
            "$ENGINE_URL/engine-rest/task?processInstanceId=$PROCESS_ID" \
            | jq -r '.[0].id // empty')
        if [ -n "$TASK_ID" ]; then
            break
        fi
        j=$((j+1))
        sleep 1
    done
    if [ -z "$TASK_ID" ]; then
        log "$DEF_KEY: WARNING — no task found within 5s. Variables are in history; skipping complete."
        return 0
    fi
    log "$DEF_KEY: task $TASK_ID"

    log "$DEF_KEY: completing task..."
    HTTP_CODE=$(curl -s -o /tmp/complete-resp.txt -w "%{http_code}" \
        -X POST "$ENGINE_URL/engine-rest/task/$TASK_ID/complete" \
        -H "Authorization: Bearer $TOKEN" \
        -H "Content-Type: application/json" \
        -d "{\"variables\":$TASK_VARS}")
    if [ "$HTTP_CODE" != "204" ]; then
        log "$DEF_KEY: WARNING — complete returned $HTTP_CODE. Body:"
        cat /tmp/complete-resp.txt
        log "$DEF_KEY: continuing (variables already in history)"
        return 0
    fi
    log "$DEF_KEY: done — process advancing per BPMN"
}

# ---------------------------------------------------------------------
# Seed personRegistration (firstName / lastName / age / objectId)
# ---------------------------------------------------------------------

PERSON_VARS='{
  "firstName": {"value":"Bart","type":"String"},
  "lastName":  {"value":"Simpson","type":"String"},
  "age":       {"value":30,"type":"Integer"},
  "objectId":  {"value":"1","type":"String"}
}'

# personRegistration also has a co-owners gateway expression on the
# applicant task — `additionalOwners == null || additionalOwners.elements().isEmpty()` —
# that throws "Cannot resolve identifier" on complete unless the variable
# exists in scope. Pass an explicit empty Json array on completion so the
# expression evaluates cleanly. Start-time variables stay shorter (the
# applicant decides whether to add co-owners on the task form).
#
# `applicantEmail` belongs to the same class: the post-approval gateway
# branches on it, and a missing variable used to crash review-task
# complete. The BPMN now guards the reference with execution.hasVariable,
# so this is no longer load-bearing, but seeding it explicitly keeps the
# seeded instance shape consistent with what the React form writes.
PERSON_COMPLETE_VARS='{
  "firstName":        {"value":"Bart","type":"String"},
  "lastName":         {"value":"Simpson","type":"String"},
  "age":              {"value":30,"type":"Integer"},
  "objectId":         {"value":"1","type":"String"},
  "applicantEmail":   {"value":"","type":"String"},
  "additionalOwners": {"value":"[]","type":"Json"}
}'

start_and_complete personRegistration "$PERSON_VARS" "$PERSON_COMPLETE_VARS"

# ---------------------------------------------------------------------
# Seed businessRegistration (applicantFirstName / applicantLastName /
# applicantAge / companyName / shareCapital / boardMembers)
# ---------------------------------------------------------------------

BIZ_VARS='{
  "companyName":         {"value":"Seed Initial OÜ","type":"String"},
  "boardMembers":        {"value":"[{\"firstName\":\"Bart\",\"lastName\":\"Simpson\",\"personalCode\":\"38501010001\"}]","type":"Json"},
  "shareCapital":        {"value":3000,"type":"Double"},
  "applicantFirstName":  {"value":"Bart","type":"String"},
  "applicantLastName":   {"value":"Simpson","type":"String"},
  "applicantAge":        {"value":30,"type":"Integer"}
}'

start_and_complete businessRegistration "$BIZ_VARS" "$BIZ_VARS"

log "all seeds done. Bart now has personRegistration + businessRegistration history."
