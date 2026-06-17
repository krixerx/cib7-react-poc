"""Headless end-to-end driver for the two ITS demo services.

Drives the same REST surface the SPA uses (via the Traefik front door on
:3000) plus the public payment endpoint, as a smoke test after
docker compose up. Not a unit test — a scriptable replay of the demo
scenarios. Usage: python scripts/transport-demo-drive.py
"""

import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

KC = "http://localhost:8180"
BASE = "http://localhost:3000"


def post_form(url, data):
    req = urllib.request.Request(url, urllib.parse.urlencode(data).encode())
    return json.load(urllib.request.urlopen(req))


def ensure_direct_grants():
    """Enable the password grant on the public cib7-frontend client.

    The realm export ships with directAccessGrantsEnabled=false (the SPA
    uses PKCE). Keycloak runs in-memory, so this runtime toggle is lost on
    container restart — which is exactly the lifetime this script needs.
    """
    admin = post_form(
        KC + "/realms/master/protocol/openid-connect/token",
        {"client_id": "admin-cli", "grant_type": "password",
         "username": "admin", "password": "admin"},
    )["access_token"]
    clients = api(KC + "/admin/realms/cib7-poc/clients?clientId=cib7-frontend", admin)
    client = clients[0]
    if not client.get("directAccessGrantsEnabled"):
        client["directAccessGrantsEnabled"] = True
        api(KC + "/admin/realms/cib7-poc/clients/" + client["id"], admin, "PUT", client)
        print("enabled directAccessGrants on cib7-frontend (runtime only)")


def token(user, password):
    return post_form(
        KC + "/realms/cib7-poc/protocol/openid-connect/token",
        {"client_id": "cib7-frontend", "grant_type": "password",
         "username": user, "password": password},
    )["access_token"]


def api(url, tok=None, method="GET", body=None):
    req = urllib.request.Request(url, method=method)
    if tok:
        req.add_header("Authorization", "Bearer " + tok)
    if body is not None:
        req.add_header("Content-Type", "application/json")
        req.data = json.dumps(body).encode()
    try:
        resp = urllib.request.urlopen(req)
    except urllib.error.HTTPError as e:
        print("HTTP", e.code, "on", method, url, "->", e.read().decode()[:500])
        raise
    raw = resp.read()
    return json.loads(raw) if raw else None


def s(v):
    return {"value": v, "type": "String"}


def wait_for_task(tok, pi, name_contains, timeout=60):
    for _ in range(timeout * 2):
        tasks = api(BASE + f"/engine-rest/task?processInstanceId={pi}", tok)
        for t in tasks:
            if name_contains.lower() in t["name"].lower():
                return t
        time.sleep(0.5)
    raise SystemExit(f"timeout waiting for task '{name_contains}' on {pi}")


def wait_for_activity(tok, pi, activity_id, timeout=60):
    for _ in range(timeout * 2):
        acts = api(BASE + f"/engine-rest/process-instance/{pi}/activity-instances", tok)
        flat = json.dumps(acts)
        if activity_id in flat:
            return
        time.sleep(0.5)
    raise SystemExit(f"timeout waiting for activity {activity_id} on {pi}")


def wait_for_end(tok, pi, timeout=90):
    for _ in range(timeout * 2):
        hist = api(BASE + f"/engine-rest/history/process-instance/{pi}", tok)
        if hist.get("state") == "COMPLETED":
            return hist
        time.sleep(0.5)
    raise SystemExit(f"timeout waiting for completion of {pi}")


def claim_and_complete(tok, task, variables):
    api(BASE + f"/engine-rest/task/{task['id']}/claim", tok, "POST",
        {"userId": task.get("assignee") or "homer"})
    api(BASE + f"/engine-rest/task/{task['id']}/complete", tok, "POST",
        {"variables": variables})


def scenario1():
    print("=== Scenario 1: Transport Vehicle Registration (happy path) ===")
    bart, homer = token("bart", "bart"), token("homer", "homer")

    pi = api(BASE + "/engine-rest/process-definition/key/transportVehicleRegistration/start",
             bart, "POST", {"variables": {}})["id"]
    print("started", pi)

    t = wait_for_task(bart, pi, "Submit vehicle registration")
    api(BASE + f"/engine-rest/task/{t['id']}/complete", bart, "POST", {"variables": {
        # The identity-prefill plugin does NOT populate these for the vehicle
        # process, and plate allocation requires ownerName (= applicantName), so
        # submit them. Use bart's real verified email so process emails reach the
        # right address (this Submit task isn't ${initiator}-guarded, so the email
        # itself isn't validated here — but it should still be correct).
        "applicantName": s("Bart Simpson"),
        "applicantEmail": s("bart@example.com"),
        "civilId": s("12345678"),
        "residencyStatus": s("citizen"),
        "registrationType": s("private"),
        "vehicleCategory": s("private-1500-3000"),
        "vin": s("OMANDEMO2026VIN01"),
        "plateOption": s("random"),
        "reservedPlateNumber": s(""),
        "sendBackReason": s(""),
    }})
    print("application submitted")

    t = wait_for_task(homer, pi, "Traffic officer review")
    fee = api(BASE + f"/engine-rest/process-instance/{pi}/variables/registrationFee", homer)
    print("officer review open; registrationFee =", fee["value"], "(expect 20)")
    claim_and_complete(homer, {**t, "assignee": "homer"}, {
        "decision": s("approve"), "rejectionReason": s(""), "sendBackReason": s(""),
    })
    print("officer approved")

    wait_for_activity(bart, pi, "Task_TransportWaitFeePayment")
    status = api(BASE + f"/api/public/payments/{pi}/status")
    print("payment page:", status["amount"], status["currency"], "-", status["item"])
    assert status["currency"] == "EUR" and status["amount"] == 20.0, status
    api(BASE + f"/api/public/payments/{pi}/confirm", method="POST", body={})
    print("fee paid")

    hist = wait_for_end(bart, pi)
    plate = api(BASE + f"/engine-rest/history/variable-instance?processInstanceId={pi}&variableName=plateNumber", bart)
    print("COMPLETED at", hist.get("endActivityId"), "| plate:", plate[0]["value"] if plate else "MISSING")
    docs = api(BASE + f"/api/documents/{pi}", bart)
    print("documents:", [(d["filename"], d["category"]) for d in docs])


def scenario1_rejection():
    print("=== Scenario 1b: system rejection (failed inspection VIN) ===")
    bart = token("bart", "bart")
    pi = api(BASE + "/engine-rest/process-definition/key/transportVehicleRegistration/start",
             bart, "POST", {"variables": {}})["id"]
    t = wait_for_task(bart, pi, "Submit vehicle registration")
    api(BASE + f"/engine-rest/task/{t['id']}/complete", bart, "POST", {"variables": {
        # The identity-prefill plugin does NOT populate these for the vehicle
        # process, and plate allocation requires ownerName (= applicantName), so
        # submit them. Use bart's real verified email so process emails reach the
        # right address (this Submit task isn't ${initiator}-guarded, so the email
        # itself isn't validated here — but it should still be correct).
        "applicantName": s("Bart Simpson"),
        "applicantEmail": s("bart@example.com"),
        "civilId": s("12345678"),
        "residencyStatus": s("citizen"),
        "registrationType": s("private"),
        "vehicleCategory": s("motorcycle"),
        "vin": s("DEMOFAILINSP01"),
        "plateOption": s("random"),
        "reservedPlateNumber": s(""),
        "sendBackReason": s(""),
    }})
    hist = wait_for_end(bart, pi)
    reason = api(BASE + f"/engine-rest/history/variable-instance?processInstanceId={pi}&variableName=eligibilityDecision", bart)
    print("COMPLETED at", hist.get("endActivityId"), "| reason:", reason[0]["value"])


def scenario2():
    print("=== Scenario 2: Learning Permit (weak vision -> hospital -> issued) ===")
    bart, homer = token("bart", "bart"), token("homer", "homer")
    pi = api(BASE + "/engine-rest/process-definition/key/transportLearningPermit/start",
             bart, "POST", {"variables": {}})["id"]
    print("started", pi)

    t = wait_for_task(bart, pi, "Apply for a learning permit")
    # This applicant task is assigned to ${initiator}, so IdentityValidationListener
    # validates applicantName/applicantEmail on complete against bart's verified
    # Keycloak account — submit his real values (matching name + verified email).
    api(BASE + f"/engine-rest/task/{t['id']}/complete", bart, "POST", {"variables": {
        "applicantName": s("Bart Simpson"),
        "applicantEmail": s("bart@example.com"),
        "civilId": s("90000001"),
        "age": {"value": 24, "type": "Integer"},
        "residencyStatus": s("citizen"),
        "hasResidentCard": {"value": False, "type": "Boolean"},
        "licenseCategory": s("light-vehicle"),
        "specialNeeds": {"value": False, "type": "Boolean"},
        "profession": s(""),
    }})
    print("application submitted (civil id 90000001 -> weak vision)")

    t = wait_for_task(homer, pi, "Police Hospital")
    print("hospital assessment open")
    claim_and_complete(homer, {**t, "assignee": "homer"}, {
        "medicalResult": s("positive"), "medicalNotes": s("Corrective lenses sufficient."),
        "rejectionReason": s(""),
    })
    print("hospital: fit to drive")

    wait_for_activity(bart, pi, "Task_TransportWaitPermitPayment")
    status = api(BASE + f"/api/public/payments/{pi}/status")
    print("payment page:", status["amount"], status["currency"], "-", status["item"])
    assert status["currency"] == "EUR" and status["amount"] == 6.0, status
    api(BASE + f"/api/public/payments/{pi}/confirm", method="POST", body={})
    print("fee paid")

    hist = wait_for_end(bart, pi)
    permit = api(BASE + f"/engine-rest/history/variable-instance?processInstanceId={pi}&variableName=permitNumber", bart)
    print("COMPLETED at", hist.get("endActivityId"), "| permit:", permit[0]["value"] if permit else "MISSING")
    docs = api(BASE + f"/api/documents/{pi}", bart)
    print("documents:", [(d["filename"], d["category"]) for d in docs])


if __name__ == "__main__":
    ensure_direct_grants()
    scenario1()
    scenario1_rejection()
    scenario2()
    print("ALL SCENARIOS PASSED")
