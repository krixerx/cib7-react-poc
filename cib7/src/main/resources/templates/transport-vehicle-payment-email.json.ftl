<#--
  Mailpit /api/v1/send payload for Task_TransportVehiclePaymentEmail in
  transport-vehicle-registration.bpmn. The officer approved; the applicant must
  pay the registration fee before plates and certificate are issued.
  Variables in scope: applicantName, applicantEmail, vehicleCategory, vin,
  registrationFee (Double from the transport-vehicle-fee DMN — defensively
  coerced because DMN→JUEL can surface numerics as locale-formatted
  Strings), frontendBaseUrl, execution.
-->
<#assign rawFee = (registrationFee!0)>
<#if rawFee?is_number>
  <#assign feeAmount = rawFee>
<#else>
  <#assign feeAmount = rawFee?replace(",", "")?replace(" ", "")?replace(" ", "")?number>
</#if>
<#assign payUrl = frontendBaseUrl + "/pay/" + execution.processInstanceId>
<#assign body>Dear ${applicantName!""},

Your vehicle registration application has been approved by the traffic
officer. To complete the registration, please pay the registration fee.

Vehicle:           ${vehicleCategory!""} (chassis ${vin!""})
Registration fee:  ${feeAmount?string("0.000")} EUR

Pay the fee here:
${payUrl}

After the payment is received, your plate number is allocated and the
registration certificate is sent to this address.

Transport Authority
Integrated Traffic System (POC)</#assign>
{
  "From": { "Email": "process@cib7-poc.local", "Name": "Transport Authority" },
  "To":   [ { "Email": "${(applicantEmail!"")?json_string}", "Name": "${(applicantName!"")?json_string}" } ],
  "Subject": "Vehicle registration approved — pay the registration fee",
  "Text": "${body?json_string}"
}
