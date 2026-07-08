<#--
  /api/documents/index-case payload for Task_TransportPermitIndexRejected.
  Card refresh: rejected. Covers both reject paths — the reason is, in order
  of preference: the officer's rejectionReason, the DMN's human-readable
  permitDecision (its outputs are "ok", "medical", or the reason sentence,
  e.g. "The legal minimum age for a learning permit is 18 years."), else a
  generic phrase so the card never leaks the "ok"/"medical" sentinels.
-->
<#assign systemReason = permitDecision!"ok">
<#if (rejectionReason!"")?has_content>
  <#assign reason = rejectionReason>
<#elseif systemReason != "ok" && systemReason != "medical" && systemReason?has_content>
  <#assign reason = systemReason>
<#else>
  <#assign reason = "eligibility requirements not met (age, eye test, existing license status, or medical assessment)">
</#if>
{
  "processInstanceId": "${execution.processInstanceId?json_string}",
  "service": "transport-learning-permit",
  "status": "rejected",
  "summary": "Driving learner permit application by ${(applicantName!"")?json_string}, civil ID ${(civilId!"")?json_string}. Status: rejected. Reason: ${reason?remove_ending(".")?json_string}. The case is closed."
}
