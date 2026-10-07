package com.poc.cib7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.cib7.documents.DocumentRenderer;
import com.poc.cib7.links.CapabilityLinks;
import freemarker.template.Configuration;
import freemarker.template.Template;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.spin.Spin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Renders every connector payload template under {@code templates/} and asserts the output parses
 * as JSON — the whole point of the {@code ?json_string} convention. Each template renders twice:
 *
 * <ul>
 *   <li><b>clean</b> — well-typed variables, the way the React forms write them;
 *   <li><b>hostile</b> — strings full of quotes/backslashes/newlines (what {@code ?json_string}
 *       exists for) and numerics surfaced as locale-formatted Strings like {@code "38,000"} (the
 *       engine→FreeMarker path the numeric-coercion blocks exist for).
 * </ul>
 *
 * <p>The template list is scanned from the source tree, so a new {@code *.ftl} is covered the
 * moment it lands. New process variables belong in {@link #baseModel()}.
 */
class FreemarkerTemplateRenderTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Path TEMPLATES_DIR =
      Path.of(System.getProperty("services.pack.dir", "../packs/test/engine"), "templates");
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

  private static Map<String, Object> modelByName(String name) {
    return "hostile".equals(name) ? hostileModel() : cleanModel();
  }

  static Stream<Arguments> templateAndModel() throws IOException {
    try (Stream<Path> files = Files.list(TEMPLATES_DIR)) {
      List<String> names =
          files
              .map(p -> p.getFileName().toString())
              .filter(n -> n.endsWith(".ftl"))
              .sorted()
              .toList();
      assertTrue(names.size() >= 17, "template scan came up short: " + names);
      return names.stream()
          .flatMap(n -> Stream.of(Arguments.of(n, "clean"), Arguments.of(n, "hostile")));
    }
  }

  private static String render(String templateName, Map<String, Object> model) throws Exception {
    Template template = FREEMARKER.getTemplate(templateName);
    StringWriter out = new StringWriter();
    template.process(model, out);
    return out.toString();
  }

  @ParameterizedTest(name = "{0} [{1}]")
  @MethodSource("templateAndModel")
  void everyTemplateRendersValidJson(String templateName, String modelName) throws Exception {
    String rendered = render(templateName, modelByName(modelName));
    JsonNode json;
    try {
      json = MAPPER.readTree(rendered);
    } catch (IOException e) {
      fail(
          templateName
              + " did not emit valid JSON: "
              + e.getMessage()
              + "\n--- output ---\n"
              + rendered);
      return;
    }
    assertTrue(json.isObject(), templateName + " should emit a JSON object");
  }

  /** The ?json_string contract end-to-end: hostile text round-trips unmangled. */
  @Test
  void hostileOwnerNameRoundTripsThroughTheEmailPayload() throws Exception {
    JsonNode json = MAPPER.readTree(render("owner-confirmation-email.json.ftl", hostileModel()));

    assertEquals(HOSTILE_NAME, json.path("To").get(0).path("Name").asText());
    assertTrue(json.path("Text").asText().contains("Hello " + HOSTILE_NAME));
  }

  /**
   * Regression: the fee invoice used {@code ?string("0.00")} on the raw shareCapital, which crashed
   * whenever the variable surfaced as a locale-formatted String — the bcard template already
   * coerced defensively, the invoice did not.
   */
  @Test
  void feeInvoiceCoercesLocaleFormattedShareCapital() throws Exception {
    JsonNode json = MAPPER.readTree(render("business-fee-invoice-pdf.json.ftl", hostileModel()));

    assertTrue(
        json.path("html").asText().contains("2500.00"),
        "share capital \"2,500\" should render as 2500.00");
  }

  /**
   * Consent and payment links carry a minted capability token, not a stored id: the confirmation
   * link is the one {@code links.consent} signs for the party, and the pay link does not expose the
   * bare process instance id (docs/security.md rules 3 and 4).
   */
  @Test
  void emailLinksCarryMintedCapabilityTokens() throws Exception {
    String owner =
        MAPPER
            .readTree(render("owner-confirmation-email.json.ftl", cleanModel()))
            .path("Text")
            .asText();
    String ownerToken = LINKS.consent(fakeExecution(), "owner", "p1");
    assertTrue(owner.contains("/consent/owner/" + ownerToken), owner);

    String tracking =
        MAPPER
            .readTree(render("applicant-tracking-email.json.ftl", cleanModel()))
            .path("Text")
            .asText();
    String applicantToken = LINKS.consent(fakeExecution(), "owner", "applicant");
    assertTrue(tracking.contains("/consent/owner/" + applicantToken), tracking);

    for (String template : List.of("approval-email.json.ftl", "business-approval-email.json.ftl")) {
      String text = MAPPER.readTree(render(template, cleanModel())).path("Text").asText();
      assertFalse(text.contains("/pay/" + PI), template + " still links the bare instance id");
      assertTrue(text.matches("(?s).*/pay/[A-Za-z0-9_-]+[.][A-Za-z0-9_-]+.*"), template);
    }
  }

  /**
   * Regression: the vehicle send-back and reviewer-reminder emails were inline JSON in the BPMN,
   * where JUEL put the reviewer's reason and the applicant's name in unescaped; a quote or a line
   * break made the payload invalid and stopped the case with an incident.
   */
  @Test
  void vehicleSendBackCarriesAHostileReasonIntact() throws Exception {
    Map<String, Object> m = hostileModel();
    JsonNode sendBack = MAPPER.readTree(render("vehicle-sendback-email.json.ftl", m));
    assertTrue(
        sendBack.path("Text").asText().contains("Reason: " + m.get("sendBackReason")),
        sendBack.toString());

    JsonNode reminder = MAPPER.readTree(render("reviewer-reminder-email.json.ftl", m));
    assertTrue(
        reminder.path("Text").asText().contains(m.get("firstName") + " " + m.get("lastName")),
        reminder.toString());
  }

  /** Invoices print the fee the backend quoted (stateFee), not one of their own. */
  @Test
  void invoicesPrintTheQuotedFee() throws Exception {
    Map<String, Object> m = cleanModel();
    m.put("stateFee", 123.5);
    withDocuments(m);
    for (String template : List.of("approval-pdf.json.ftl", "business-fee-invoice-pdf.json.ftl")) {
      String html = MAPPER.readTree(render(template, m)).path("html").asText();
      assertTrue(html.contains("&euro;123.50"), template);
    }
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
