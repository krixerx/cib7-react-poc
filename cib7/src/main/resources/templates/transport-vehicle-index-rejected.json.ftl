<#--
  /api/documents/index-case payload for Task_TransportVehicleIndexRejected.
  Card refresh: rejected. Covers both reject paths — the reason is, in order
  of preference: the officer's rejectionReason, the DMN's human-readable
  eligibilityDecision (its non-"ok" output IS the reason sentence, e.g.
  "The vehicle has not passed the technical inspection."), else a generic
  phrase so the card never leaks the "ok" sentinel.
-->
<#assign systemReason = eligibilityDecision!"ok">
<#if (rejectionReason!"")?has_content>
  <#assign reason = rejectionReason>
<#elseif systemReason != "ok" && systemReason?has_content>
  <#assign reason = systemReason>
<#else>
  <#assign reason = "automated eligibility checks failed (vehicle inspection, insurance, or outstanding restrictions)">
</#if>
{
  "processInstanceId": "${execution.processInstanceId?json_string}",
  "service": "transport-vehicle-registration",
  "status": "rejected",
  "summary": "Transport vehicle registration application by ${(applicantName!"")?json_string} for vehicle VIN ${(vin!"")?json_string}. Status: rejected. Reason: ${reason?remove_ending(".")?json_string}. The case is closed."
}
