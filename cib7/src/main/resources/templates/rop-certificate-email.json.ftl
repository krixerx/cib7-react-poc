<#--
  Mailpit /api/v1/send payload for Task_RopCertificateEmail in
  rop-vehicle-registration.bpmn. Demo step 5 — notification to receive the
  vehicle license, with the registration certificate PDF attached and
  plate-collection instructions (demo step 4).
  Variables in scope: applicantName, applicantEmail, plateNumber, vin,
  vehicleCategory, certificatePdfBytes, certificatePdfFilename.
-->
<#assign body>Dear ${applicantName!""},

Congratulations — your vehicle is registered.

Plate number:  ${plateNumber!""}
Vehicle:       ${vehicleCategory!""} (chassis ${vin!""})

The attached Vehicle Registration Certificate is your official
registration document. You can collect the physical plates from the
license-plate printing company or any Vehicle Registration Services
Center by presenting your civil ID.

Royal Oman Police — General Traffic Department
Integrated Traffic System (POC)</#assign>
{
  "From": { "Email": "process@cib7-poc.local", "Name": "Royal Oman Police — General Traffic Department" },
  "To":   [ { "Email": "${(applicantEmail!"")?json_string}", "Name": "${(applicantName!"")?json_string}" } ],
  "Subject": "Your vehicle is registered — plate ${(plateNumber!"")?json_string}",
  "Text": "${body?json_string}",
  "Attachments": [
    {
      "Filename": "${(certificatePdfFilename!"rop-registration-certificate.pdf")?json_string}",
      "ContentType": "application/pdf",
      "Content": "${pdf.encode(certificatePdfBytes)}"
    }
  ]
}
