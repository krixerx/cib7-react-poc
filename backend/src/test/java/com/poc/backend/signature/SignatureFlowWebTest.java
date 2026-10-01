package com.poc.backend.signature;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.poc.backend.engine.EngineClient;
import com.poc.backend.engine.EngineClient.ProcessInstanceRef;
import com.poc.backend.founder.FounderSignatureController;
import com.poc.backend.links.CapabilityLinkVerifier;
import com.poc.backend.links.TestLinks;
import com.poc.backend.owner.OwnerConfirmationController;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * The public co-signing endpoints under docs/security.md rule 3: the signed capability token is the
 * only credential, it names the case, the party and the round, and status responses never carry
 * another party's email or any token.
 *
 * <p>Security filters are off: {@code /api/public/**} is permit-all anyway, and the checks under
 * test live in the controllers and {@link CapabilityLinkVerifier}.
 */
@WebMvcTest(controllers = {OwnerConfirmationController.class, FounderSignatureController.class})
@AutoConfigureMockMvc(addFilters = false)
@Import(CapabilityLinkVerifier.class)
@TestPropertySource(properties = "app.links.secret=" + TestLinks.SECRET)
class SignatureFlowWebTest {

  private static final String PI = "pi-42";
  private static final String OTHER_PI = "pi-43";
  private static final long ROUND = 5L;
  private static final String NOT_FOUND_BODY = "unknown_token";

  @Autowired MockMvc mvc;
  @MockitoBean EngineClient engine;

  final ObjectMapper json = new ObjectMapper();

  ObjectNode approved() {
    ObjectNode e = json.createObjectNode();
    e.put("status", "approved");
    e.put("signedAt", "2026-06-12T10:00:00Z");
    return e;
  }

  ArrayNode parties(String... nameEmail) {
    ArrayNode list = json.createArrayNode();
    for (int i = 0; i < nameEmail.length; i += 2) {
      ObjectNode p = list.addObject();
      p.put("partyId", "p" + (i / 2 + 1));
      p.put("name", nameEmail[i]);
      p.put("email", nameEmail[i + 1]);
    }
    return list;
  }

  // =====================================================================
  // Owner-confirmation flow (vehicleRegistration)
  // =====================================================================

  @Nested
  class OwnerFlow {

    static final String BASE = "/api/public/owner-confirmations";

    void stubCase(JsonNode confirmations, Boolean rejected, Boolean sent) {
      when(engine.findActiveById(PI)).thenReturn(new ProcessInstanceRef(PI, "vehicleRegistration"));
      when(engine.getRawVariable(PI, "consentRound")).thenReturn(ROUND);
      when(engine.getStringVariable(PI, "firstName")).thenReturn("Ants");
      when(engine.getStringVariable(PI, "lastName")).thenReturn("Avaldaja");
      when(engine.getJsonVariable(PI, "additionalOwners"))
          .thenReturn(parties("Olga Omanik", "olga@example.com", "Peeter", "peeter@example.com"));
      when(engine.getJsonVariable(PI, "ownerConfirmations")).thenReturn(confirmations);
      when(engine.getBooleanVariable(PI, "rejectedByOwner")).thenReturn(rejected);
      when(engine.getBooleanVariable(PI, "sentToProcess")).thenReturn(sent);
    }

    ObjectNode applicantOnly() {
      ObjectNode c = json.createObjectNode();
      c.set("applicant", approved());
      return c;
    }

    String token(String partyId) {
      return TestLinks.mint(PI, partyId, "owner", ROUND);
    }

    @Test
    void statusShowsNamesAndStatesButNoEmailsOrTokens() throws Exception {
      stubCase(applicantOnly(), null, null);
      String token = token("p1");

      String body =
          mvc.perform(get(BASE + "/{token}/status", token))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.applicantName").value("Ants Avaldaja"))
              .andExpect(jsonPath("$.state").value("pending"))
              .andExpect(jsonPath("$.currentOwner.partyId").value("p1"))
              .andExpect(jsonPath("$.currentOwner.name").value("Olga Omanik"))
              .andExpect(jsonPath("$.currentOwner.isApplicant").value(false))
              .andExpect(jsonPath("$.owners.length()").value(3))
              .andExpect(jsonPath("$.owners[0].status").value("approved"))
              .andExpect(jsonPath("$.owners[2].name").value("Peeter"))
              .andReturn()
              .getResponse()
              .getContentAsString();

      assertThat(body)
          .doesNotContain("@example.com")
          .doesNotContain("\"email\"")
          .doesNotContain("\"token\"")
          .doesNotContain(token.split("[.]")[1]);
    }

    @Test
    void forgedTokenIs404() throws Exception {
      stubCase(applicantOnly(), null, null);
      String forged = TestLinks.mint("not-the-secret", PI, "p1", "owner", ROUND, far());

      mvc.perform(get(BASE + "/{token}/status", forged))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value(NOT_FOUND_BODY))
          .andExpect(
              jsonPath("$.message").value("This confirmation link is unknown or has expired."));
    }

    @Test
    void tamperedPartyIs404() throws Exception {
      stubCase(applicantOnly(), null, null);
      String real = token("p1");
      String forged = token("p2").split("[.]")[0] + "." + real.split("[.]")[1];

      mvc.perform(get(BASE + "/{token}/status", forged))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value(NOT_FOUND_BODY));
    }

    @Test
    void expiredTokenIs404() throws Exception {
      stubCase(applicantOnly(), null, null);
      String expired =
          TestLinks.mint(
              TestLinks.SECRET, PI, "p1", "owner", ROUND, Instant.now().getEpochSecond() - 1);

      mvc.perform(get(BASE + "/{token}/status", expired))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value(NOT_FOUND_BODY));
    }

    @Test
    void earlierRoundIs404AndCannotSign() throws Exception {
      stubCase(applicantOnly(), null, null);
      String old = TestLinks.mint(PI, "p1", "owner", ROUND - 1);

      mvc.perform(get(BASE + "/{token}/status", old)).andExpect(status().isNotFound());
      mvc.perform(
              post(BASE + "/{token}", old)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"decision\":\"approve\"}"))
          .andExpect(status().isNotFound());
      verify(engine, never()).correlateMessage(anyString(), anyString(), anyMap(), anyMap());
    }

    @Test
    void founderTokenDoesNotOpenOwnerEndpoints() throws Exception {
      stubCase(applicantOnly(), null, null);

      mvc.perform(get(BASE + "/{token}/status", TestLinks.mint(PI, "p1", "founder", ROUND)))
          .andExpect(status().isNotFound());
    }

    @Test
    void partyNotOnTheCaseIs404() throws Exception {
      stubCase(applicantOnly(), null, null);

      mvc.perform(get(BASE + "/{token}/status", token("p9"))).andExpect(status().isNotFound());
    }

    @Test
    void tokenForOneCaseCannotActOnAnother() throws Exception {
      stubCase(applicantOnly(), null, null);
      // The other case exists and is waiting, but nothing in the request can point at it: the
      // instance id comes from the signed token only.
      when(engine.findActiveById(OTHER_PI))
          .thenReturn(new ProcessInstanceRef(OTHER_PI, "vehicleRegistration"));
      when(engine.getRawVariable(OTHER_PI, "consentRound")).thenReturn(ROUND);
      when(engine.correlateMessage(anyString(), anyString(), anyMap(), anyMap())).thenReturn(true);

      mvc.perform(
              post(BASE + "/{token}", token("p1"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      "{\"decision\":\"approve\",\"processInstanceId\":\""
                          + OTHER_PI
                          + "\",\"partyId\":\"p2\"}"))
          .andExpect(status().isOk());

      verify(engine)
          .correlateMessage(eq("OwnerConfirmation"), eq(PI), eq(Map.of("partyId", "p1")), any());
      verify(engine, never()).correlateMessage(anyString(), eq(OTHER_PI), anyMap(), anyMap());
      verify(engine, never()).setJsonVariable(eq(OTHER_PI), anyString(), any());
    }

    @Test
    void approveRecordsTheTokenPartyAndCorrelatesOnPartyId() throws Exception {
      stubCase(applicantOnly(), null, null);
      when(engine.correlateMessage(anyString(), anyString(), anyMap(), anyMap())).thenReturn(true);

      mvc.perform(
              post(BASE + "/{token}", token("p2"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"decision\":\"approve\"}"))
          .andExpect(status().isOk());

      ArgumentCaptor<JsonNode> written = ArgumentCaptor.forClass(JsonNode.class);
      verify(engine).setJsonVariable(eq(PI), eq("ownerConfirmations"), written.capture());
      assertThat(written.getValue().path("p2").path("status").asText()).isEqualTo("approved");
      assertThat(written.getValue().has("p1")).isFalse();
      verify(engine)
          .correlateMessage(eq("OwnerConfirmation"), eq(PI), eq(Map.of("partyId", "p2")), any());
      verify(engine, never()).setBooleanVariable(anyString(), anyString(), anyBoolean());
    }

    @Test
    void rejectFlagsTheCaseWithTheSignersName() throws Exception {
      stubCase(applicantOnly(), null, null);
      when(engine.correlateMessage(anyString(), anyString(), anyMap(), anyMap())).thenReturn(true);

      mvc.perform(
              post(BASE + "/{token}", token("p1"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"decision\":\"reject\",\"reason\":\" wrong VIN \"}"))
          .andExpect(status().isOk());

      verify(engine).setBooleanVariable(PI, "rejectedByOwner", true);
      verify(engine)
          .setStringVariable(
              PI, "sendBackReason", "Owner Olga Omanik rejected the application: wrong VIN");
    }

    @Test
    void applicantLinkCannotSign() throws Exception {
      stubCase(applicantOnly(), null, null);

      mvc.perform(
              post(BASE + "/{token}", token("applicant"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"decision\":\"approve\"}"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("applicant_auto_confirmed"));
    }

    @Test
    void secondSignatureIsRefused() throws Exception {
      ObjectNode c = applicantOnly();
      c.set("p1", approved());
      stubCase(c, null, null);

      mvc.perform(
              post(BASE + "/{token}", token("p1"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"decision\":\"approve\"}"))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("already_signed"));
    }

    @Test
    void sendToProcessNeedsEverySignature() throws Exception {
      ObjectNode c = applicantOnly();
      c.set("p1", approved());
      stubCase(c, null, null);

      mvc.perform(post(BASE + "/{token}/send-to-process", token("applicant")))
          .andExpect(status().isConflict())
          .andExpect(jsonPath("$.code").value("not_ready"));

      c.set("p2", approved());
      when(engine.correlateMessage(anyString(), anyString(), anyMap(), anyMap())).thenReturn(true);
      mvc.perform(post(BASE + "/{token}/send-to-process", token("applicant")))
          .andExpect(status().isOk());
      verify(engine).correlateMessage("SendToProcess", PI, Map.of(), Map.of("sentToProcess", true));
    }

    @Test
    void badDecisionIs400() throws Exception {
      mvc.perform(
              post(BASE + "/{token}", token("p1"))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"decision\":\"reject\"}"))
          .andExpect(status().isBadRequest());
    }
  }

  // =====================================================================
  // Founder-signature flow (businessRegistration)
  // =====================================================================

  @Nested
  class FounderFlow {

    static final String BASE = "/api/public/founder-signatures";

    void stubCase(JsonNode signatures) {
      when(engine.findActiveById(PI))
          .thenReturn(new ProcessInstanceRef(PI, "businessRegistration"));
      when(engine.getRawVariable(PI, "consentRound")).thenReturn(ROUND);
      when(engine.getStringVariable(PI, "applicantFirstName")).thenReturn("Frida");
      when(engine.getStringVariable(PI, "applicantLastName")).thenReturn("Asutaja");
      when(engine.getStringVariable(PI, "companyName")).thenReturn("Näidis OÜ");
      when(engine.getJsonVariable(PI, "additionalFounders"))
          .thenReturn(parties("Karl Kaasasutaja", "karl@example.com"));
      when(engine.getJsonVariable(PI, "founderSignatures")).thenReturn(signatures);
    }

    @Test
    void statusHasCompanyAndNoEmails() throws Exception {
      ObjectNode s = json.createObjectNode();
      s.set("applicant", approved());
      stubCase(s);

      String body =
          mvc.perform(
                  get(BASE + "/{token}/status", TestLinks.mint(PI, "applicant", "founder", ROUND)))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.companyName").value("Näidis OÜ"))
              .andExpect(jsonPath("$.currentFounder.isApplicant").value(true))
              .andExpect(jsonPath("$.founders.length()").value(2))
              .andExpect(jsonPath("$.founders[1].name").value("Karl Kaasasutaja"))
              .andReturn()
              .getResponse()
              .getContentAsString();

      assertThat(body).doesNotContain("karl@example.com").doesNotContain("\"token\"");
    }

    @Test
    void signCorrelatesOnThePartyFromTheToken() throws Exception {
      ObjectNode s = json.createObjectNode();
      s.set("applicant", approved());
      stubCase(s);
      when(engine.correlateMessage(anyString(), anyString(), anyMap(), anyMap())).thenReturn(true);

      mvc.perform(
              post(BASE + "/{token}", TestLinks.mint(PI, "p1", "founder", ROUND))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content("{\"decision\":\"approve\"}"))
          .andExpect(status().isOk());

      verify(engine)
          .correlateMessage(eq("FounderSignature"), eq(PI), eq(Map.of("partyId", "p1")), any());
    }

    @Test
    void ownerTokenDoesNotOpenFounderEndpoints() throws Exception {
      stubCase(json.createObjectNode());

      mvc.perform(get(BASE + "/{token}/status", TestLinks.mint(PI, "p1", "owner", ROUND)))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.code").value(NOT_FOUND_BODY));
    }
  }

  private static long far() {
    return Instant.now().getEpochSecond() + 3600;
  }
}
