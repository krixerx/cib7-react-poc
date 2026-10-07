package com.poc.cib7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.cib7.documents.DocumentRenderer;
import com.poc.cib7.links.CapabilityLinks;
import freemarker.template.Configuration;
import freemarker.template.Template;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.spin.Spin;
import org.junit.jupiter.api.Test;

/**
 * The core's {@code documents} bean and PDF helper as the pack's templates use them: PDF documents
 * HTML-escape every value and carry the pack's brand, case variables cannot shadow the beans, and
 * document names stay in their folder. What each pack template sends is its spec's examples, run by
 * {@code TemplateExamplesTest} (a pack check).
 */
class FreemarkerTemplateRenderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final String PI = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";

  private static final Configuration FREEMARKER = buildConfiguration();

  private static Configuration buildConfiguration() {
    Configuration cfg = new Configuration(Configuration.VERSION_2_3_31);
    cfg.setClassLoaderForTemplateLoading(
        FreemarkerTemplateRenderTest.class.getClassLoader(), "templates");
    cfg.setDefaultEncoding("UTF-8");
    cfg.setLocale(Locale.US);
    return cfg;
  }

  /** Stand-in for the DelegateExecution the connector exposes as {@code execution}. */
  static DelegateExecution fakeExecution() {
    DelegateExecution execution = mock(DelegateExecution.class);
    when(execution.getProcessInstanceId()).thenReturn(PI);
    when(execution.getVariable(CapabilityLinks.ROUND_VARIABLE)).thenReturn(1_700_000_000_000L);
    return execution;
  }

  static final CapabilityLinks LINKS =
      new CapabilityLinks(
          "test-secret",
          Duration.ofDays(14),
          Duration.ofDays(30),
          Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));

  /**
   * The engine's `documents` bean, reading the pack's documents/ and branding/ from the classpath.
   */
  static final DocumentRenderer DOCUMENTS =
      new DocumentRenderer(
          Map.<String, Object>of("frontendBaseUrl", "http://localhost:3000", "links", LINKS)::get,
          "documents",
          "branding");

  /**
   * Adds the `documents` bean and lets the fake execution hand the model's variables to it, as
   * {@code execution.getVariables()} does in the engine.
   */
  private static Map<String, Object> withDocuments(Map<String, Object> m) {
    m.put("documents", DOCUMENTS);
    DelegateExecution execution = (DelegateExecution) m.get("execution");
    when(execution.getVariables()).thenReturn(Variables.fromMap(m));
    return m;
  }

  private static Object spinJson(Object value) {
    try {
      return Spin.JSON(MAPPER.writeValueAsString(value));
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }

  /** Variables shared by both models — ids, URLs, helper beans, PDF bytes. */
  private static Map<String, Object> baseModel() {
    Map<String, Object> m = new HashMap<>();
    m.put("execution", fakeExecution());
    m.put("pdf", new PdfHelper());
    m.put("links", LINKS);
    m.put("frontendBaseUrl", "http://localhost:3000");
    m.put("initiator", "lisa");
    m.put("applicantEmail", "ants@example.com");
    m.put("autoDecision", "approve");
    m.put("decision", "approve");
    m.put("applicantResidency", "e-resident");
    m.put("objectId", "VIN-1234567");
    m.put("stateFee", 75.0);
    m.put("approvalPdfBytes", "fake-approval-pdf".getBytes(StandardCharsets.UTF_8));
    m.put("feeInvoicePdfBytes", "fake-invoice-pdf".getBytes(StandardCharsets.UTF_8));
    m.put("bcardPdfBytes", "fake-bcard-pdf".getBytes(StandardCharsets.UTF_8));
    m.put("certificatePdfBytes", "fake-certificate-pdf".getBytes(StandardCharsets.UTF_8));
    m.put("permitPdfBytes", "fake-permit-pdf".getBytes(StandardCharsets.UTF_8));
    return m;
  }

  static Map<String, Object> cleanModel() {
    Map<String, Object> m = baseModel();
    m.put("firstName", "Ants");
    m.put("lastName", "Avaldaja");
    m.put("applicantFirstName", "Frida");
    m.put("applicantLastName", "Asutaja");
    m.put("companyName", "Näidis OÜ");
    m.put("vehicleMake", "Škoda");
    m.put("vehicleModel", "Octavia");
    m.put("sendBackReason", "Please fix the share capital.");
    m.put("price", 38_000);
    m.put("shareCapital", 2_500);
    m.put(
        "additionalOwners",
        spinJson(
            List.of(Map.of("name", "Olga Omanik", "email", "olga@example.com", "partyId", "p1"))));
    m.put(
        "additionalFounders",
        spinJson(
            List.of(
                Map.of("name", "Karl Kaasasutaja", "email", "karl@example.com", "partyId", "p1"))));
    m.put(
        "boardMembers",
        spinJson(
            List.of(
                Map.of(
                    "firstName", "Mari", "lastName", "Maasikas", "personalCode", "48001010000"))));
    m.put(
        "owner",
        spinJson(Map.of("name", "Olga Omanik", "email", "olga@example.com", "partyId", "p1")));
    m.put(
        "founder",
        spinJson(Map.of("name", "Karl Kaasasutaja", "email", "karl@example.com", "partyId", "p1")));
    m.put(
        "pendingIdDocument",
        spinJson(
            Map.of(
                "pendingKey",
                "pending/lisa/u1/id.png",
                "filename",
                "id.png",
                "contentType",
                "image/png")));
    m.put(
        "pendingAoaDocument",
        spinJson(
            Map.of(
                "pendingKey",
                "pending/lisa/u2/aoa.pdf",
                "filename",
                "aoa.pdf",
                "contentType",
                "application/pdf")));
    return withDocuments(m);
  }

  static final String HOSTILE_NAME = "Ka\"rl \\ O'Kaasa\nsutaja";

  static Map<String, Object> hostileModel() {
    Map<String, Object> m = baseModel();
    m.put("firstName", "An\"ts\\");
    m.put("lastName", "Ava\nldaja\t<b>");
    m.put("applicantFirstName", "Fri\"da");
    m.put("applicantLastName", "Asu\\taja");
    m.put("companyName", "Näidis \"Quoted\" \\ OÜ");
    m.put("vehicleMake", "Ško\"da");
    m.put("vehicleModel", "Octa\nvia");
    m.put("sendBackReason", "Line one,\nthen \"line two\" with a \\ backslash.");
    // Numerics as locale-formatted Strings — the engine→FreeMarker shape
    // the ?is_number coercion blocks defend against.
    m.put("price", "38,000");
    m.put("shareCapital", "2,500");
    m.put(
        "additionalOwners",
        spinJson(
            List.of(Map.of("name", HOSTILE_NAME, "email", "olga@example.com", "partyId", "p1"))));
    m.put(
        "additionalFounders",
        spinJson(
            List.of(Map.of("name", HOSTILE_NAME, "email", "karl@example.com", "partyId", "p1"))));
    m.put(
        "boardMembers",
        spinJson(
            List.of(
                Map.of(
                    "firstName",
                    "Ma\"ri",
                    "lastName",
                    "Maa\nsikas",
                    "personalCode",
                    "48001010000"))));
    m.put(
        "owner",
        spinJson(Map.of("name", HOSTILE_NAME, "email", "olga@example.com", "partyId", "p1")));
    m.put(
        "founder",
        spinJson(Map.of("name", HOSTILE_NAME, "email", "karl@example.com", "partyId", "p1")));
    m.put(
        "pendingIdDocument",
        spinJson(
            Map.of(
                "pendingKey",
                "pending/lisa/u1/id.png",
                "filename",
                "evil \"name\".png",
                "contentType",
                "image/png")));
    m.put(
        "pendingAoaDocument",
        spinJson(
            Map.of(
                "pendingKey",
                "pending/lisa/u2/aoa.pdf",
                "filename",
                "aoa\n.pdf",
                "contentType",
                "application/pdf")));
    return withDocuments(m);
  }

  private static String render(String templateName, Map<String, Object> model) throws Exception {
    Template template = FREEMARKER.getTemplate(templateName);
    StringWriter out = new StringWriter();
    template.process(model, out);
    return out.toString();
  }

  /** PDF documents are .ftlh: every value is HTML-escaped without a ?html in the document. */
  @Test
  void pdfDocumentsEscapeValuesForHtml() throws Exception {
    String html =
        MAPPER.readTree(render("approval-pdf.json.ftl", hostileModel())).path("html").asText();

    assertTrue(html.contains("ldaja\t&lt;b&gt;"), html);
    assertFalse(html.contains("<b>"), "a variable's markup reached the PDF");
  }

  /** Every PDF carries the pack's brand: the portal name and the logo, inlined as a data URI. */
  @Test
  void pdfDocumentsCarryTheBrand() throws Exception {
    for (String template :
        List.of(
            "approval-pdf.json.ftl",
            "certificate-pdf.json.ftl",
            "bcard-pdf.json.ftl",
            "business-fee-invoice-pdf.json.ftl")) {
      String html = MAPPER.readTree(render(template, cleanModel())).path("html").asText();
      assertTrue(html.contains("Issued through eRegistrations"), template);
      assertTrue(html.contains("<img src=\"data:image/svg+xml;base64,"), template);
    }
  }

  /**
   * A case variable cannot stand in for the brand or the link minting: `brand`, `links` and
   * `execution` are set after the variables (docs/security.md rule 2).
   */
  @Test
  void variablesCannotShadowDocumentBeans() throws Exception {
    Map<String, Object> m = cleanModel();
    m.put("brand", Map.of("name", "Evil Corp", "primary", "red;}", "logo", "javascript:x"));
    m.put("links", "not the links bean");
    withDocuments(m);
    DelegateExecution execution = (DelegateExecution) m.get("execution");

    String html = DOCUMENTS.html("vehicle-certificate", execution);
    assertTrue(html.contains("Issued through eRegistrations"), html);
    assertFalse(html.contains("Evil Corp"), html);
    String text = DOCUMENTS.text("vehicle-approval", execution);
    assertTrue(text.matches("(?s).*/pay/[A-Za-z0-9_-]+[.][A-Za-z0-9_-]+.*"), text);
  }

  @Test
  void documentNamesCannotLeaveTheirFolder() {
    DelegateExecution execution = (DelegateExecution) cleanModel().get("execution");
    for (String name : List.of("../templates/approval-email.json", "Pdf", "a/b", "")) {
      assertThrows(IllegalArgumentException.class, () -> DOCUMENTS.html(name, execution), name);
    }
  }

  @Test
  void pdfHelperEncodeDecodeRoundTrips() {
    PdfHelper pdf = new PdfHelper();
    byte[] bytes = "some pdf bytes  ÿ".getBytes(StandardCharsets.ISO_8859_1);
    assertEquals(
        new String(bytes, StandardCharsets.ISO_8859_1),
        new String(pdf.decode(pdf.encode(bytes)), StandardCharsets.ISO_8859_1));
  }
}
