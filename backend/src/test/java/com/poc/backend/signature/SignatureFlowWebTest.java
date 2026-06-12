package com.poc.backend.signature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.poc.backend.engine.EngineClient;
import com.poc.backend.founder.FounderSignatureController;
import com.poc.backend.owner.OwnerConfirmationController;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Characterization tests pinning the CURRENT behavior of the two mirror-image public signing
 * controllers ({@link OwnerConfirmationController} for vehicleRegistration, {@link
 * FounderSignatureController} for businessRegistration) before the planned merge into one
 * abstraction. Every assertion documents what the code does today — including quirks — not what it
 * should do.
 *
 * <p>Security filters are disabled: both surfaces are under {@code /api/public/**}, which
 * SecurityConfig permits unauthenticated anyway, and importing SecurityConfig would drag in its
 * {@code app.internal-task-token} @Value dependency for no extra coverage.
 *
 * <p>Known quirks pinned here:
 *
 * <ul>
 *   <li>The documented {@code confirmed_waiting} state is never produced by {@code buildStatus} — a
 *       partially signed case reports {@code pending}.
 *   <li>The signatures/confirmations JSON variable is written BEFORE message correlation, so a
 *       {@code not_waiting} 409 still leaves the signature persisted.
 * </ul>
 */
@WebMvcTest(controllers = {OwnerConfirmationController.class, FounderSignatureController.class})
@AutoConfigureMockMvc(addFilters = false)
class SignatureFlowWebTest {

  private static final String PI = "pi-42";

  @Autowired MockMvc mvc;
  @MockitoBean EngineClient engine;

  final ObjectMapper json = new ObjectMapper();

  ObjectNode entry(String status, String reason) {
    ObjectNode e = json.createObjectNode();
    e.put("status", status);
    e.put("signedAt", "2026-06-12T10:00:00Z");
    if (reason != null) {
      e.put("reason", reason);
    }
    return e;
  }

  // =====================================================================
  // Owner-confirmation flow (vehicleRegistration)
  // =====================================================================

  @Nested
  class OwnerFlow {

    static final String BASE = "/api/public/owner-confirmations";
    static final String APPLICANT_TOKEN = "tok-applicant";
    static final String OWNER_TOKEN = "tok-owner";

    /** Token resolves through the slow path: not the applicant, found in additionalOwners[]. */
    void stubOwnerLookup() {
      when(engine.findActiveByVariable("vehicleRegistration", "applicantToken", OWNER_TOKEN))
          .thenReturn(List.of());
      when(engine.findActive("vehicleRegistration")).thenReturn(List.of(PI));
      ArrayNode owners = json.createArrayNode();
      ObjectNode owner = owners.addObject();
      owner.put("name", "Olga Omanik");
      owner.put("email", "olga@example.com");
      owner.put("token", OWNER_TOKEN);
      when(engine.getJsonVariable(PI, "additionalOwners")).thenReturn(owners);
    }

    void stubVars(JsonNode confirmations, Boolean rejected, Boolean sent) {
      when(engine.getStringVariable(PI, "firstName")).thenReturn("Ants");
      when(engine.getStringVariable(PI, "lastName")).thenReturn("Avaldaja");
      when(engine.getStringVariable(PI, "applicantEmail")).thenReturn("ants@example.com");
      when(engine.getStringVariable(PI, "applicantToken")).thenReturn(APPLICANT_TOKEN);
      when(engine.getJsonVariable(PI, "ownerConfirmations")).thenReturn(confirmations);
      when(engine.getBooleanVariable(PI, "rejectedByOwner")).thenReturn(rejected);
      when(engine.getBooleanVariable(PI, "sentToProcess")).thenReturn(sent);
    }

    ObjectNode bothApproved() {
      ObjectNode confirmations = json.createObjectNode();
      confirmations.set(APPLICANT_TOKEN, entry("approved", null));
      confirmations.set(OWNER_TOKEN, entry("approved", null));
      return confirmations;
    }

    @Test
    void statusWithUnknownTokenIs404WithErrorShape() throws Exception {
      when(engine.findActiveByVariable(anyString(), anyString(), anyString()))
          .thenReturn(List.of());
      when(engine.findActive("vehicleRegistration")).thenReturn(List.of());

      mvc.perform(get(BASE + "/{token}/status", "no-such-token"))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("unknown_token"))
          .andExpect(
              jsonPath("$.message").value("This confirmation link is unknown or has expired."));
    }

    @Test
    void statusForPendingOwnerReportsPendingNotConfirmedWaiting() throws Exception {
      stubOwnerLookup();
      // Applicant already approved, this owner not yet — the javadoc promises
      // confirmed_waiting for partially signed cases, but buildStatus only
      // ever emits pending/ready_to_send/sent/rejected. Pin "pending".
      ObjectNode confirmations = json.createObjectNode();
      confirmations.set(APPLICANT_TOKEN, entry("approved", null));
      stubVars(confirmations, null, null);

      mvc.perform(get(BASE + "/{token}/status", OWNER_TOKEN))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.processInstanceId").value(PI))
          .andExpect(jsonPath("$.applicantName").value("Ants Avaldaja"))
          .andExpect(jsonPath("$.state").value("pending"))
          .andExpect(jsonPath("$.currentOwner.token").value(OWNER_TOKEN))
          .andExpect(jsonPath("$.currentOwner.isApplicant").value(false))
          .andExpect(jsonPath("$.currentOwner.status").value("pending"))
          .andExpect(jsonPath("$.owners.length()").value(2))
          .andExpect(jsonPath("$.owners[0].isApplicant").value(true))
          .andExpect(jsonPath("$.owners[0].status").value("approved"))
          .andExpect(jsonPath("$.rejectedBy").doesNotExist());
    }

    @Test
    void approveWritesConfirmationsBeforeCorrelatingOwnerConfirmation() throws Exception {
      stubOwnerLookup();
      stubVars(null, null, null);
      // 1st read: pre-check (nothing signed). 2nd read: buildStatus after the
      // write — simulate the engine now returning both signatures.
      when(engine.getJsonVariable(PI, "ownerConfirmations")).thenReturn(null, bothApproved());
      when(engine.correlateMessage(
              "OwnerConfirmation", PI, Map.of("ownerToken", OWNER_TOKEN), Map.of()))
          .thenReturn(true);

      mvc.perform(
              post(BASE + "/{token}", OWNER_TOKEN)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"decision\":\"approve\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.state").value("ready_to_send"))
          .andExpect(jsonPath("$.currentOwner.status").value("approved"));

      ArgumentCaptor<JsonNode> written = ArgumentCaptor.forClass(JsonNode.class);
      InOrder order = inOrder(engine);
      order.verify(engine).setJsonVariable(eq(PI), eq("ownerConfirmations"), written.capture());
      order
          .verify(engine)
          .correlateMessage("OwnerConfirmation", PI, Map.of("ownerToken", OWNER_TOKEN), Map.of());

      JsonNode mine = written.getValue().get(OWNER_TOKEN);
      assertThat(mine).isNotNull();
      assertThat(mine.path("status").asText()).isEqualTo("approved");
      assertThat(mine.path("signedAt").asText()).isNotBlank();
      assertThat(mine.has("reason")).isFalse();

      verify(engine, never()).setBooleanVariable(anyString(), anyString(), anyBoolean());
      verify(engine, never()).setStringVariable(anyString(), anyString(), anyString());
    }

    @Test
    void rejectSetsFlagAndFormattedSendBackReason() throws Exception {
      stubOwnerLookup();
      stubVars(null, null, null);
      ObjectNode afterReject = json.createObjectNode();
      afterReject.set(OWNER_TOKEN, entry("rejected", "Price is wrong"));
      when(engine.getJsonVariable(PI, "ownerConfirmations")).thenReturn(null, afterReject);
      // Flag flips between the pre-check read and the buildStatus read.
      when(engine.getBooleanVariable(PI, "rejectedByOwner")).thenReturn(null, true);
      when(engine.correlateMessage(
              "OwnerConfirmation", PI, Map.of("ownerToken", OWNER_TOKEN), Map.of()))
          .thenReturn(true);

      mvc.perform(
              post(BASE + "/{token}", OWNER_TOKEN)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"decision\":\"reject\",\"reason\":\"  Price is wrong  \"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.state").value("rejected"))
          .andExpect(jsonPath("$.rejectedBy").value("Olga Omanik"))
          .andExpect(jsonPath("$.rejectionReason").value("Price is wrong"));

      verify(engine).setBooleanVariable(PI, "rejectedByOwner", true);
      // Exact format string, with the reason trimmed.
      verify(engine)
          .setStringVariable(
              PI, "sendBackReason", "Owner Olga Omanik rejected the application: Price is wrong");
    }

    @Test
    void rejectWithoutReasonIs400() throws Exception {
      mvc.perform(
              post(BASE + "/{token}", OWNER_TOKEN)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"decision\":\"reject\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("bad_request"))
          .andExpect(jsonPath("$.message").value("A reason is required when rejecting."));
    }

    @Test
    void unknownDecisionIs400() throws Exception {
      mvc.perform(
              post(BASE + "/{token}", OWNER_TOKEN)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"decision\":\"maybe\"}"))
          .andExpect(status().isBadRequest())
          .andExpect(jsonPath("$.code").value("bad_request"));
    }

    @Test
    void applicantTokenCannotPostADecision() throws Exception {
      when(engine.findActiveByVariable("vehicleRegistration", "applicantToken", APPLICANT_TOKEN))
          .thenReturn(List.of(PI));
      when(engine.getStringVariable(PI, "firstName")).thenReturn("Ants");
      when(engine.getStringVariable(PI, "lastName")).thenReturn("Avaldaja");

      mvc.perform(
              post(BASE + "/{token}", APPLICANT_TOKEN)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"decision\":\"approve\"}"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("applicant_auto_confirmed"));
    }

    @Test
    void approveWhenAlreadySignedIs409() throws Exception {
      stubOwnerLookup();
      ObjectNode confirmations = json.createObjectNode();
      confirmations.set(OWNER_TOKEN, entry("approved", null));
      stubVars(confirmations, null, null);

      mvc.perform(
              post(BASE + "/{token}", OWNER_TOKEN)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"decision\":\"approve\"}"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("already_signed"));

      verify(engine, never()).setJsonVariable(anyString(), anyString(), any());
      verify(engine, never()).correlateMessage(anyString(), anyString(), any(), any());
    }

    @Test
    void approveWhenAnotherOwnerRejectedIs409() throws Exception {
      stubOwnerLookup();
      stubVars(null, true, null);

      mvc.perform(
              post(BASE + "/{token}", OWNER_TOKEN)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"decision\":\"approve\"}"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("already_rejected"));
    }

    @Test
    void approveWhenAlreadySentIs409() throws Exception {
      stubOwnerLookup();
      stubVars(null, null, true);

      mvc.perform(
              post(BASE + "/{token}", OWNER_TOKEN)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"decision\":\"approve\"}"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("already_sent"));
    }

    @Test
    void approveWhenNoExecutionWaitingIs409ButSignatureWasAlreadyWritten() throws Exception {
      stubOwnerLookup();
      stubVars(null, null, null);
      when(engine.correlateMessage(
              "OwnerConfirmation", PI, Map.of("ownerToken", OWNER_TOKEN), Map.of()))
          .thenReturn(false);

      mvc.perform(
              post(BASE + "/{token}", OWNER_TOKEN)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"decision\":\"approve\"}"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("not_waiting"));

      // Quirk pinned: the confirmation JSON was persisted even though the
      // request answered 409 — the write happens before correlation.
      verify(engine).setJsonVariable(eq(PI), eq("ownerConfirmations"), any());
    }

    @Test
    void sendToProcessWhenNotAllSignedIs409NotReady() throws Exception {
      stubOwnerLookup();
      ObjectNode confirmations = json.createObjectNode();
      confirmations.set(APPLICANT_TOKEN, entry("approved", null));
      stubVars(confirmations, null, null);

      mvc.perform(post(BASE + "/{token}/send-to-process", OWNER_TOKEN))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("not_ready"))
          .andExpect(jsonPath("$.message").value("Not all owners have signed yet."));

      verify(engine, never()).correlateMessage(anyString(), anyString(), any(), any());
    }

    @Test
    void sendToProcessHappyPathCorrelatesSendToProcessWithProcessVariable() throws Exception {
      stubOwnerLookup();
      stubVars(bothApproved(), null, null);
      // Pre-check + ready-check read null; the post-correlate read sees true.
      when(engine.getBooleanVariable(PI, "sentToProcess")).thenReturn(null, null, true);
      when(engine.correlateMessage("SendToProcess", PI, Map.of(), Map.of("sentToProcess", true)))
          .thenReturn(true);

      mvc.perform(post(BASE + "/{token}/send-to-process", OWNER_TOKEN))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.state").value("sent"));

      verify(engine).correlateMessage("SendToProcess", PI, Map.of(), Map.of("sentToProcess", true));
    }

    @Test
    void sendToProcessWhenNoExecutionWaitingIs409NotWaiting() throws Exception {
      stubOwnerLookup();
      stubVars(bothApproved(), null, null);
      when(engine.correlateMessage("SendToProcess", PI, Map.of(), Map.of("sentToProcess", true)))
          .thenReturn(false);

      mvc.perform(post(BASE + "/{token}/send-to-process", OWNER_TOKEN))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("not_waiting"));
    }

    @Test
    void sendToProcessWhenAlreadySentIs409() throws Exception {
      stubOwnerLookup();
      stubVars(bothApproved(), null, true);

      mvc.perform(post(BASE + "/{token}/send-to-process", OWNER_TOKEN))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("already_sent"));
    }
  }

  // =====================================================================
  // Founder-signature flow (businessRegistration) — mirror of the above
  // =====================================================================

  @Nested
  class FounderFlow {

    static final String BASE = "/api/public/founder-signatures";
    static final String APPLICANT_TOKEN = "tok-f-applicant";
    static final String FOUNDER_TOKEN = "tok-f-founder";

    void stubFounderLookup() {
      when(engine.findActiveByVariable("businessRegistration", "applicantToken", FOUNDER_TOKEN))
          .thenReturn(List.of());
      when(engine.findActive("businessRegistration")).thenReturn(List.of(PI));
      ArrayNode founders = json.createArrayNode();
      ObjectNode founder = founders.addObject();
      founder.put("name", "Karl Kaasasutaja");
      founder.put("email", "karl@example.com");
      founder.put("token", FOUNDER_TOKEN);
      when(engine.getJsonVariable(PI, "additionalFounders")).thenReturn(founders);
    }

    void stubVars(JsonNode signatures, Boolean rejected, Boolean sent) {
      when(engine.getStringVariable(PI, "applicantFirstName")).thenReturn("Frida");
      when(engine.getStringVariable(PI, "applicantLastName")).thenReturn("Asutaja");
      when(engine.getStringVariable(PI, "applicantEmail")).thenReturn("frida@example.com");
      when(engine.getStringVariable(PI, "applicantToken")).thenReturn(APPLICANT_TOKEN);
      when(engine.getStringVariable(PI, "companyName")).thenReturn("Näidis OÜ");
      when(engine.getJsonVariable(PI, "founderSignatures")).thenReturn(signatures);
      when(engine.getBooleanVariable(PI, "rejectedByFounder")).thenReturn(rejected);
      when(engine.getBooleanVariable(PI, "sentToRegister")).thenReturn(sent);
    }

    ObjectNode bothApproved() {
      ObjectNode signatures = json.createObjectNode();
      signatures.set(APPLICANT_TOKEN, entry("approved", null));
      signatures.set(FOUNDER_TOKEN, entry("approved", null));
      return signatures;
    }

    @Test
    void statusWithUnknownTokenIs404WithErrorShape() throws Exception {
      when(engine.findActiveByVariable(anyString(), anyString(), anyString()))
          .thenReturn(List.of());
      when(engine.findActive("businessRegistration")).thenReturn(List.of());

      mvc.perform(get(BASE + "/{token}/status", "no-such-token"))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value("unknown_token"))
          .andExpect(jsonPath("$.message").value("This signing link is unknown or has expired."));
    }

    @Test
    void statusForPendingFounderIncludesCompanyNameAndReportsPending() throws Exception {
      stubFounderLookup();
      ObjectNode signatures = json.createObjectNode();
      signatures.set(APPLICANT_TOKEN, entry("approved", null));
      stubVars(signatures, null, null);

      mvc.perform(get(BASE + "/{token}/status", FOUNDER_TOKEN))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.processInstanceId").value(PI))
          .andExpect(jsonPath("$.applicantName").value("Frida Asutaja"))
          .andExpect(jsonPath("$.companyName").value("Näidis OÜ"))
          // Same quirk as the owner flow: never confirmed_waiting.
          .andExpect(jsonPath("$.state").value("pending"))
          .andExpect(jsonPath("$.currentFounder.token").value(FOUNDER_TOKEN))
          .andExpect(jsonPath("$.currentFounder.isApplicant").value(false))
          .andExpect(jsonPath("$.founders.length()").value(2))
          .andExpect(jsonPath("$.founders[0].isApplicant").value(true));
    }

    @Test
    void approveWritesSignaturesBeforeCorrelatingFounderSignature() throws Exception {
      stubFounderLookup();
      stubVars(null, null, null);
      when(engine.getJsonVariable(PI, "founderSignatures")).thenReturn(null, bothApproved());
      when(engine.correlateMessage(
              "FounderSignature", PI, Map.of("founderToken", FOUNDER_TOKEN), Map.of()))
          .thenReturn(true);

      mvc.perform(
              post(BASE + "/{token}", FOUNDER_TOKEN)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"decision\":\"approve\"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.state").value("ready_to_send"))
          .andExpect(jsonPath("$.currentFounder.status").value("approved"));

      ArgumentCaptor<JsonNode> written = ArgumentCaptor.forClass(JsonNode.class);
      InOrder order = inOrder(engine);
      order.verify(engine).setJsonVariable(eq(PI), eq("founderSignatures"), written.capture());
      order
          .verify(engine)
          .correlateMessage(
              "FounderSignature", PI, Map.of("founderToken", FOUNDER_TOKEN), Map.of());

      JsonNode mine = written.getValue().get(FOUNDER_TOKEN);
      assertThat(mine).isNotNull();
      assertThat(mine.path("status").asText()).isEqualTo("approved");
      assertThat(mine.path("signedAt").asText()).isNotBlank();
      assertThat(mine.has("reason")).isFalse();

      verify(engine, never()).setBooleanVariable(anyString(), anyString(), anyBoolean());
      verify(engine, never()).setStringVariable(anyString(), anyString(), anyString());
    }

    @Test
    void rejectSetsFlagAndFormattedSendBackReason() throws Exception {
      stubFounderLookup();
      stubVars(null, null, null);
      ObjectNode afterReject = json.createObjectNode();
      afterReject.set(FOUNDER_TOKEN, entry("rejected", "Numbers are off"));
      when(engine.getJsonVariable(PI, "founderSignatures")).thenReturn(null, afterReject);
      when(engine.getBooleanVariable(PI, "rejectedByFounder")).thenReturn(null, true);
      when(engine.correlateMessage(
              "FounderSignature", PI, Map.of("founderToken", FOUNDER_TOKEN), Map.of()))
          .thenReturn(true);

      mvc.perform(
              post(BASE + "/{token}", FOUNDER_TOKEN)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"decision\":\"reject\",\"reason\":\" Numbers are off \"}"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.state").value("rejected"))
          .andExpect(jsonPath("$.rejectedBy").value("Karl Kaasasutaja"))
          .andExpect(jsonPath("$.rejectionReason").value("Numbers are off"));

      verify(engine).setBooleanVariable(PI, "rejectedByFounder", true);
      // "Co-founder" prefix and "registration" wording — diverges from the
      // owner flow's "Owner ... rejected the application: ...".
      verify(engine)
          .setStringVariable(
              PI,
              "sendBackReason",
              "Co-founder Karl Kaasasutaja rejected the registration: Numbers are off");
    }

    @Test
    void approveWhenAlreadySignedIs409() throws Exception {
      stubFounderLookup();
      ObjectNode signatures = json.createObjectNode();
      signatures.set(FOUNDER_TOKEN, entry("approved", null));
      stubVars(signatures, null, null);

      mvc.perform(
              post(BASE + "/{token}", FOUNDER_TOKEN)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"decision\":\"approve\"}"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("already_signed"));

      verify(engine, never()).setJsonVariable(anyString(), anyString(), any());
      verify(engine, never()).correlateMessage(anyString(), anyString(), any(), any());
    }

    @Test
    void approveWhenAnotherFounderRejectedIs409() throws Exception {
      stubFounderLookup();
      stubVars(null, true, null);

      mvc.perform(
              post(BASE + "/{token}", FOUNDER_TOKEN)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"decision\":\"approve\"}"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("already_rejected"));
    }

    @Test
    void approveWhenAlreadySubmittedIs409() throws Exception {
      stubFounderLookup();
      stubVars(null, null, true);

      mvc.perform(
              post(BASE + "/{token}", FOUNDER_TOKEN)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"decision\":\"approve\"}"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("already_sent"));
    }

    @Test
    void approveWhenNoExecutionWaitingIs409ButSignatureWasAlreadyWritten() throws Exception {
      stubFounderLookup();
      stubVars(null, null, null);
      when(engine.correlateMessage(
              "FounderSignature", PI, Map.of("founderToken", FOUNDER_TOKEN), Map.of()))
          .thenReturn(false);

      mvc.perform(
              post(BASE + "/{token}", FOUNDER_TOKEN)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"decision\":\"approve\"}"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("not_waiting"));

      verify(engine).setJsonVariable(eq(PI), eq("founderSignatures"), any());
    }

    @Test
    void submitToRegisterWhenNotAllSignedIs409NotReady() throws Exception {
      stubFounderLookup();
      ObjectNode signatures = json.createObjectNode();
      signatures.set(APPLICANT_TOKEN, entry("approved", null));
      stubVars(signatures, null, null);

      mvc.perform(post(BASE + "/{token}/submit-to-register", FOUNDER_TOKEN))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("not_ready"))
          .andExpect(jsonPath("$.message").value("Not all co-founders have signed yet."));

      verify(engine, never()).correlateMessage(anyString(), anyString(), any(), any());
    }

    @Test
    void submitToRegisterHappyPathCorrelatesSubmitToRegister() throws Exception {
      stubFounderLookup();
      stubVars(bothApproved(), null, null);
      when(engine.getBooleanVariable(PI, "sentToRegister")).thenReturn(null, null, true);
      when(engine.correlateMessage(
              "SubmitToRegister", PI, Map.of(), Map.of("sentToRegister", true)))
          .thenReturn(true);

      mvc.perform(post(BASE + "/{token}/submit-to-register", FOUNDER_TOKEN))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.state").value("sent"));

      verify(engine)
          .correlateMessage("SubmitToRegister", PI, Map.of(), Map.of("sentToRegister", true));
    }

    @Test
    void submitToRegisterWhenNoExecutionWaitingIs409NotWaiting() throws Exception {
      stubFounderLookup();
      stubVars(bothApproved(), null, null);
      when(engine.correlateMessage(
              "SubmitToRegister", PI, Map.of(), Map.of("sentToRegister", true)))
          .thenReturn(false);

      mvc.perform(post(BASE + "/{token}/submit-to-register", FOUNDER_TOKEN))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("not_waiting"));
    }

    @Test
    void submitToRegisterWhenAlreadySentIs409() throws Exception {
      stubFounderLookup();
      stubVars(bothApproved(), null, true);

      mvc.perform(post(BASE + "/{token}/submit-to-register", FOUNDER_TOKEN))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("already_sent"));
    }
  }
}
