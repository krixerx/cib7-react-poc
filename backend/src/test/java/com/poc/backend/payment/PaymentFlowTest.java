package com.poc.backend.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.poc.backend.engine.EngineClient;
import com.poc.backend.engine.EngineClient.ProcessInstanceRef;
import com.poc.backend.links.TestLinks;
import com.poc.backend.payment.mockprovider.CallbackSender;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The payment flow of docs/security.md rule 4 through the real filter chains, JPA store and mock
 * provider: a browser can start a checkout but only a callback signed with the provider secret, for
 * the amount the server computed, makes a case paid.
 *
 * <p>The mock provider's HTTP delivery is replaced by a {@link CallbackSender} that captures the
 * signed request, which the test then posts to the callback endpoint itself.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
    properties = {
      "app.links.secret=" + TestLinks.SECRET,
      "app.payment.provider-secret=" + PaymentFlowTest.PROVIDER_SECRET
    })
class PaymentFlowTest {

  static final String PROVIDER_SECRET = "test-provider-secret";
  private static final String PI = "pi-pay-flow";
  private static final String CALLBACK = "/api/public/payments/callback";

  @Autowired MockMvc mvc;
  @Autowired PaymentSessionRepository sessions;
  @MockitoBean EngineClient engine;
  @MockitoBean CallbackSender sender;
  @MockitoBean S3Client s3Client;
  @MockitoBean S3Presigner s3Presigner;

  private final ObjectMapper json = new ObjectMapper();
  private final AtomicReference<byte[]> sentBody = new AtomicReference<>();
  private final AtomicReference<String> sentSignature = new AtomicReference<>();

  @BeforeEach
  void setUp() {
    sessions.deleteAll();
    when(engine.findActiveById(PI)).thenReturn(new ProcessInstanceRef(PI, "vehicleRegistration"));
    when(engine.getRawVariable(PI, "price")).thenReturn(12_000);
    when(engine.correlateMessage(anyString(), anyString(), anyMap(), anyMap())).thenReturn(true);
    when(sender.send(any(), anyString()))
        .thenAnswer(
            inv -> {
              sentBody.set(inv.getArgument(0));
              sentSignature.set(inv.getArgument(1));
              return true;
            });
  }

  private static String payToken(String pi) {
    return TestLinks.mint(pi, "applicant", "payment", 0);
  }

  private String checkout() throws Exception {
    String body =
        mvc.perform(post("/api/public/payments/{token}/checkout", payToken(PI)))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode response = json.readTree(body);
    assertThat(response.path("redirectUrl").asText())
        .isEqualTo("/mock-bank/" + response.path("sessionId").asText());
    return response.path("sessionId").asText();
  }

  private static String sign(String secret, byte[] body) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return HexFormat.of().formatHex(mac.doFinal(body));
  }

  private org.springframework.test.web.servlet.ResultActions deliver(byte[] body, String signature)
      throws Exception {
    return mvc.perform(
        post(CALLBACK)
            .contentType(MediaType.APPLICATION_JSON)
            .header(PaymentCallbackController.SIGNATURE_HEADER, signature)
            .content(body));
  }

  @Test
  void statusShowsTheBillWithoutPersonalData() throws Exception {
    when(engine.getStringVariable(anyString(), anyString())).thenReturn("SECRET-PERSONAL");

    String body =
        mvc.perform(get("/api/public/payments/{token}", payToken(PI)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.amount").value(75.0))
            .andExpect(jsonPath("$.currency").value("EUR"))
            .andExpect(jsonPath("$.recipient").value("Transpordiamet"))
            .andExpect(jsonPath("$.status").value("pending"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).doesNotContain("SECRET-PERSONAL").doesNotContain(PI);
  }

  @Test
  void forgedOrWrongPurposeTokenIs404() throws Exception {
    String forged = TestLinks.mint("not-the-secret", PI, "applicant", "payment", 0, 4_102_444_800L);
    mvc.perform(get("/api/public/payments/{token}", forged)).andExpect(status().isNotFound());
    mvc.perform(post("/api/public/payments/{token}/checkout", forged))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/public/payments/{token}", TestLinks.mint(PI, "applicant", "owner", 0)))
        .andExpect(status().isNotFound());
  }

  @Test
  void oldConfirmEndpointIsGone() throws Exception {
    mvc.perform(post("/api/public/payments/{piId}/confirm", PI))
        .andExpect(result -> assertThat(result.getResponse().getStatus()).isIn(404, 405));
    verify(engine, never()).correlateMessage(eq("PaymentReceived"), anyString(), any(), any());
  }

  @Test
  void signedCallbackMarksPaidAndCorrelatesOnce() throws Exception {
    String sessionId = checkout();
    mvc.perform(post("/api/public/mock-provider/sessions/{id}/pay", sessionId))
        .andExpect(status().isOk());

    // The provider signed with the shared secret; the merchant accepts it.
    assertThat(sentSignature.get()).isEqualTo(sign(PROVIDER_SECRET, sentBody.get()));
    deliver(sentBody.get(), sentSignature.get())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PAID"));
    // A retried delivery is a no-op.
    deliver(sentBody.get(), sentSignature.get()).andExpect(status().isOk());

    verify(engine, times(1))
        .correlateMessage(
            eq("PaymentReceived"),
            eq(PI),
            eq(Map.of()),
            eq(
                Map.of(
                    "paymentReceived",
                    true,
                    "paymentReference",
                    PaymentController.reference(PI),
                    "paidAmount",
                    75.0)));
    assertThat(sessions.findById(sessionId).orElseThrow().getStatus())
        .isEqualTo(PaymentSession.Status.PAID);

    mvc.perform(get("/api/public/payments/{token}", payToken(PI)))
        .andExpect(jsonPath("$.status").value("paid"));
    mvc.perform(post("/api/public/payments/{token}/checkout", payToken(PI)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("already_paid"));
  }

  @Test
  void badSignatureIs401AndNothingIsCorrelated() throws Exception {
    String sessionId = checkout();
    byte[] body = callbackBody(sessionId, PaymentController.reference(PI), "75.00", "PAID");

    deliver(body, sign("not-the-provider-secret", body))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("invalid_signature"));
    deliver(body, "zz-not-hex").andExpect(status().isUnauthorized());
    mvc.perform(post(CALLBACK).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnauthorized());

    verify(engine, never()).correlateMessage(eq("PaymentReceived"), anyString(), any(), any());
    assertThat(sessions.findById(sessionId).orElseThrow().getStatus())
        .isEqualTo(PaymentSession.Status.PENDING);
  }

  @Test
  void wrongAmountOrCurrencyIsRejected() throws Exception {
    String sessionId = checkout();
    String ref = PaymentController.reference(PI);

    byte[] cheap = callbackBody(sessionId, ref, "0.01", "PAID");
    deliver(cheap, sign(PROVIDER_SECRET, cheap))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("amount_mismatch"));

    byte[] usd =
        ("{\"sessionId\":\""
                + sessionId
                + "\",\"reference\":\""
                + ref
                + "\",\"amount\":\"75.00\",\"currency\":\"USD\",\"status\":\"PAID\"}")
            .getBytes(StandardCharsets.UTF_8);
    deliver(usd, sign(PROVIDER_SECRET, usd)).andExpect(status().isBadRequest());

    verify(engine, never()).correlateMessage(eq("PaymentReceived"), anyString(), any(), any());
    assertThat(sessions.findById(sessionId).orElseThrow().getStatus())
        .isEqualTo(PaymentSession.Status.PENDING);
  }

  @Test
  void cancelledPaymentIsNotPaid() throws Exception {
    String sessionId = checkout();
    mvc.perform(post("/api/public/mock-provider/sessions/{id}/cancel", sessionId))
        .andExpect(status().isOk());
    deliver(sentBody.get(), sentSignature.get()).andExpect(status().isOk());

    assertThat(sessions.findById(sessionId).orElseThrow().getStatus())
        .isEqualTo(PaymentSession.Status.CANCELLED);
    verify(engine, never()).correlateMessage(eq("PaymentReceived"), anyString(), any(), any());
    mvc.perform(get("/api/public/payments/{token}", payToken(PI)))
        .andExpect(jsonPath("$.status").value("pending"));
  }

  @Test
  void tokenForAnotherCaseCannotPayThisOne() throws Exception {
    when(engine.findActiveById("pi-other")).thenReturn(null);

    mvc.perform(post("/api/public/payments/{token}/checkout", payToken("pi-other")))
        .andExpect(status().isNotFound());
    assertThat(sessions.count()).isZero();
  }

  @Test
  void signedInStarterGetsAWorkingPaymentLinkOthersGet404() throws Exception {
    when(engine.getHistoricStartUserId(PI)).thenReturn("bart");

    String body =
        mvc.perform(
                get("/api/cases/{pi}/payment-link", PI)
                    .with(jwt().jwt(j -> j.claim("preferred_username", "bart"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    String path = json.readTree(body).path("path").asText();
    assertThat(path).startsWith("/pay/");
    mvc.perform(get("/api/public/payments/{token}", path.substring("/pay/".length())))
        .andExpect(status().isOk());

    mvc.perform(
            get("/api/cases/{pi}/payment-link", PI)
                .with(jwt().jwt(j -> j.claim("preferred_username", "homer"))))
        .andExpect(status().isNotFound());
    mvc.perform(get("/api/cases/{pi}/payment-link", PI)).andExpect(status().isUnauthorized());
  }

  private static byte[] callbackBody(
      String sessionId, String reference, String amount, String outcome) {
    return ("{\"sessionId\":\""
            + sessionId
            + "\",\"reference\":\""
            + reference
            + "\",\"amount\":\""
            + amount
            + "\",\"currency\":\"EUR\",\"status\":\""
            + outcome
            + "\"}")
        .getBytes(StandardCharsets.UTF_8);
  }
}
