package com.poc.cib7.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.poc.cib7.PdfHelper;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.cibseven.bpm.engine.delegate.DelegateExecution;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.spin.Spin;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Renders every connector payload template with its spec's examples (docs/platform-api.md, "Service
 * examples"): what a template sends is pack data, tested with the pack.
 *
 * <p>A spec {@code service-tasks/<task>.md} with a {@code payload-template:} has one or more
 * sections whose heading starts with {@code Example} ({@code ## Example}, {@code ## Example: a
 * hostile name}). Each holds the case variables in a {@code json} code block (an object or a list
 * becomes a Spin JSON variable, as the engine stores it; {@code {"$bytes": "text"}} becomes a
 * {@code byte[]}) and optionally a table {@code | Path | Expected |}: a JSON pointer into the
 * rendered payload and a JSON literal it must equal, {@code contains "text"} or {@code matches
 * "regex"}. The core supplies what the engine does: {@code execution}, {@code pdf}, {@code links},
 * {@code documents} and {@code frontendBaseUrl}.
 *
 * <p>Every example also renders with a hostile suffix (a quote, a backslash, a line break and
 * markup) appended to each string of text (not to a formatted number or a lower-case code such as
 * an id, an enum value or an email), and must still give a JSON object: the {@code ?json_string}
 * contract, for every template of every pack.
 */
@Tag("pack")
class TemplateExamplesTest {

  private static final Path SPECS =
      Path.of(System.getProperty("services.docs.dir", "../packs/test/docs/business/services"));
  private static final Pattern TEMPLATE = Pattern.compile("payload-template:\\s*(\\S+)");
  private static final Pattern RULE = Pattern.compile("^(contains|matches)\\s+(\".*\")$");
  private static final String HOSTILE = "\"\\\n<b>";
  private static final Pattern FORMATTED_NUMBER = Pattern.compile("[0-9][0-9 .,]*");
  private static final Pattern CODE = Pattern.compile("[a-z0-9][a-z0-9._/@+-]*");
  private static final String PI = "a1b2c3d4-e5f6-7890-abcd-ef1234567890";

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final Configuration FREEMARKER = configuration();
  private static final CapabilityLinks LINKS =
      new CapabilityLinks(
          "test-secret",
          Duration.ofDays(14),
          Duration.ofDays(30),
          Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
  private static final DocumentRenderer DOCUMENTS =
      new DocumentRenderer(
          Map.<String, Object>of("frontendBaseUrl", "http://localhost:3000", "links", LINKS)::get,
          "documents",
          "branding");

  private static Configuration configuration() {
    Configuration cfg = new Configuration(Configuration.VERSION_2_3_31);
    cfg.setClassLoaderForTemplateLoading(TemplateExamplesTest.class.getClassLoader(), "templates");
    cfg.setDefaultEncoding("UTF-8");
    cfg.setLocale(Locale.US);
    return cfg;
  }

  @Test
  void everyTemplateRendersWhatItsSpecsExamplesSay() throws IOException {
    List<String> problems = new ArrayList<>();
    for (Path spec : taskSpecs()) {
      String md = Files.readString(spec);
      Matcher template = TEMPLATE.matcher(md);
      if (!template.find()) {
        continue;
      }
      String at = SPECS.relativize(spec).toString().replace('\\', '/');
      List<String> examples = SpecTables.headingsStartingWith(md, "Example");
      if (examples.isEmpty()) {
        problems.add(at + ": has a payload template but no ## Example section");
        continue;
      }
      for (String heading : examples) {
        String where = at + " (" + heading + ")";
        JsonNode variables;
        try {
          variables = JSON.readTree(SpecTables.codeBlock(md, heading).orElse(""));
        } catch (IOException e) {
          variables = null;
        }
        if (variables == null || !variables.isObject()) {
          problems.add(where + ": needs the case variables as a JSON object in a code block");
          continue;
        }
        JsonNode rendered = render(where, template.group(1), variables, problems);
        if (rendered != null) {
          SpecTables.under(md, heading)
              .ifPresent(table -> expectations(where, rendered, table, problems));
        }
        render(where + " [hostile]", template.group(1), hostile(variables), problems);
      }
    }
    assertEquals(List.of(), problems);
  }

  private static JsonNode render(
      String where, String template, JsonNode variables, List<String> problems) {
    String out;
    try {
      Template t = FREEMARKER.getTemplate(template);
      StringWriter writer = new StringWriter();
      t.process(model(variables), writer);
      out = writer.toString();
    } catch (Exception e) {
      problems.add(where + ": " + template + " failed: " + e.getMessage());
      return null;
    }
    try {
      JsonNode json = JSON.readTree(out);
      if (json != null && json.isObject()) {
        return json;
      }
    } catch (IOException e) {
      // reported below
    }
    problems.add(where + ": " + template + " is no JSON object:\n" + out);
    return null;
  }

  private static void expectations(
      String where, JsonNode rendered, SpecTables.Table table, List<String> problems) {
    if (!table.header().contains("Path") || !table.header().contains("Expected")) {
      return;
    }
    for (Map<String, String> row : table.rows()) {
      String path = SpecTables.unquote(row.get("Path"));
      String expected = SpecTables.unquote(row.get("Expected"));
      JsonNode actual = rendered.at(path);
      if (actual.isMissingNode()) {
        problems.add(where + ": " + path + " is missing");
        continue;
      }
      try {
        Matcher rule = RULE.matcher(expected);
        if (rule.matches()) {
          String text = JSON.readTree(rule.group(2)).asText();
          boolean ok =
              rule.group(1).equals("contains")
                  ? actual.asText().contains(text)
                  : Pattern.compile(text, Pattern.DOTALL).matcher(actual.asText()).find();
          if (!ok) {
            problems.add(where + ": " + path + " does not " + expected + ": " + actual.asText());
          }
        } else if (!JSON.readTree(expected).equals(actual)) {
          problems.add(where + ": " + path + " is " + actual + ", expected " + expected);
        }
      } catch (IOException e) {
        problems.add(where + ": expectation " + expected + " is no JSON literal");
      }
    }
  }

  /** The variables with the hostile suffix on every string, nested ones too. */
  private static JsonNode hostile(JsonNode node) {
    if (node.isTextual()) {
      // A number the engine may hold as formatted text ("38,000") stays a number: the value
      // schemas keep free text out of numeric variables.
      // A code (an id, an enum value, a user name, an email, a media type) is never free text
      // in the engine either: the policy, the value schemas or the engine set its shape.
      return FORMATTED_NUMBER.matcher(node.textValue()).matches()
              || CODE.matcher(node.textValue()).matches()
          ? node
          : TextNode.valueOf(node.textValue() + HOSTILE);
    }
    if (node.isObject()) {
      if (node.has("$bytes")) {
        return node;
      }
      ObjectNode copy = JSON.createObjectNode();
      node.fields().forEachRemaining(e -> copy.set(e.getKey(), hostile(e.getValue())));
      return copy;
    }
    if (node.isArray()) {
      ArrayNode copy = JSON.createArrayNode();
      node.forEach(n -> copy.add(hostile(n)));
      return copy;
    }
    return node;
  }

  /** The FreeMarker model as the engine's script engine builds it for a connector. */
  private static Map<String, Object> model(JsonNode variables) {
    Map<String, Object> m = new HashMap<>();
    for (Iterator<Map.Entry<String, JsonNode>> it = variables.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      m.put(e.getKey(), variable(e.getValue()));
    }
    DelegateExecution execution = mock(DelegateExecution.class);
    when(execution.getProcessInstanceId()).thenReturn(PI);
    when(execution.getVariable(CapabilityLinks.ROUND_VARIABLE))
        .thenReturn(m.getOrDefault(CapabilityLinks.ROUND_VARIABLE, 1_700_000_000_000L));
    m.put("execution", execution);
    m.put("pdf", new PdfHelper());
    m.put("links", LINKS);
    m.put("frontendBaseUrl", "http://localhost:3000");
    m.put("documents", DOCUMENTS);
    when(execution.getVariables()).thenReturn(Variables.fromMap(m));
    return m;
  }

  private static Object variable(JsonNode v) {
    if (v.isObject() && v.has("$bytes")) {
      return v.get("$bytes").asText().getBytes(StandardCharsets.UTF_8);
    }
    if (v.isObject() || v.isArray()) {
      return Spin.JSON(v.toString());
    }
    if (v.isNull()) {
      return null;
    }
    if (v.isIntegralNumber()) {
      return v.canConvertToInt() ? (Object) v.intValue() : (Object) v.longValue();
    }
    if (v.isNumber()) {
      return v.doubleValue();
    }
    if (v.isBoolean()) {
      return v.booleanValue();
    }
    return v.asText();
  }

  private static List<Path> taskSpecs() throws IOException {
    if (!Files.isDirectory(SPECS)) {
      return List.of();
    }
    try (Stream<Path> walk = Files.walk(SPECS)) {
      return walk.filter(
              p ->
                  p.getParent() != null
                      && p.getParent().getFileName().toString().equals("service-tasks")
                      && p.toString().endsWith(".md"))
          .sorted()
          .toList();
    }
  }
}
