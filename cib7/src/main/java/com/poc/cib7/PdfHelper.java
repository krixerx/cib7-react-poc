package com.poc.cib7;

import org.springframework.stereotype.Component;

import java.util.Base64;

/**
 * Tiny base64 ↔ byte[] bridge exposed as a JUEL/FreeMarker bean.
 *
 * <p>Exists because of one H2/Camunda gotcha: CIB seven stores String-typed
 * process variables inline in {@code ACT_HI_VARINST.TEXT_} which is capped at
 * {@code VARCHAR(4000)}. The base64-encoded approval PDF (~35 kB) blows past
 * that, killing the job flush. Bytes-typed variables spill to
 * {@code ACT_GE_BYTEARRAY} with no size limit, so we:
 *
 * <ol>
 *   <li>decode base64 → byte[] before storing the variable (see Task_GeneratePdf
 *       in vehicle-registration.bpmn — {@code ${pdf.decode(...)}});</li>
 *   <li>encode byte[] → base64 inside approval-email.json.ftl when assembling
 *       the Mailpit attachment ({@code ${pdf.encode(approvalPdfBytes)}}).</li>
 * </ol>
 *
 * <p>Bean name {@code "pdf"} keeps the BPMN/FreeMarker call sites short.
 */
@Component("pdf")
public class PdfHelper {

    public byte[] decode(String base64) {
        return Base64.getDecoder().decode(base64);
    }

    public String encode(byte[] bytes) {
        return Base64.getEncoder().encodeToString(bytes);
    }
}
