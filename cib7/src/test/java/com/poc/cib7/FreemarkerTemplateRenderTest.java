package com.poc.cib7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import freemarker.template.Configuration;
import freemarker.template.Template;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
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
  private static final Path TEMPLATES_DIR = Path.of("src", "main", "resources", "templates");
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
  public static class FakeExecution {
    public String getProcessInstanceId() {
      return PI;
    }
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
    m.put("execution", new FakeExecution());
    m.put("pdf", new PdfHelper());
    m.put("frontendBaseUrl", "http://localhost:3000");
    m.put("initiator", "lisa");
    m.put("applicantToken", "tok-applicant-1");
    m.put("applicantEmail", "ants@example.com");
    m.put("autoDecision", "approve");
    m.put("decision", "approve");
    m.put("applicantResidency", "e-resident");
    m.put("objectId", "VIN-1234567");
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
            List.of(
                Map.of("name", "Olga Omanik", "email", "olga@example.com", "token", "tok-o1"))));
    m.put(
        "additionalFounders",
        spinJson(
            List.of(
                Map.of(
                    "name", "Karl Kaasasutaja", "email", "karl@example.com", "token", "tok-f1"))));
    m.put(
        "boardMembers",
        spinJson(
            List.of(
                Map.of(
                    "firstName", "Mari", "lastName", "Maasikas", "personalCode", "48001010000"))));
    m.put(
        "owner",
        spinJson(Map.of("name", "Olga Omanik", "email", "olga@example.com", "token", "tok-o1")));
    m.put(
        "founder",
        spinJson(
            Map.of("name", "Karl Kaasasutaja", "email", "karl@example.com", "token", "tok-f1")));
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
    return m;
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
            List.of(Map.of("name", HOSTILE_NAME, "email", "olga@example.com", "token", "tok-o1"))));
    m.put(
        "additionalFounders",
        spinJson(
            List.of(Map.of("name", HOSTILE_NAME, "email", "karl@example.com", "token", "tok-f1"))));
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
        spinJson(Map.of("name", HOSTILE_NAME, "email", "olga@example.com", "token", "tok-o1")));
    m.put(
        "founder",
        spinJson(Map.of("name", HOSTILE_NAME, "email", "karl@example.com", "token", "tok-f1")));
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
    return m;
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

  @Test
  void pdfHelperEncodeDecodeRoundTrips() {
    PdfHelper pdf = new PdfHelper();
    byte[] bytes = "some pdf bytes  ÿ".getBytes(StandardCharsets.ISO_8859_1);
    assertEquals(
        new String(bytes, StandardCharsets.ISO_8859_1),
        new String(pdf.decode(pdf.encode(bytes)), StandardCharsets.ISO_8859_1));
  }
}
