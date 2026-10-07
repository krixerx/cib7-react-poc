package com.poc.cib7;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import javax.xml.parsers.DocumentBuilderFactory;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Holds the pack to the project's spec-first rule: every BPMN service task and decision has a spec
 * under {@code packs/reference/docs/business/services/<service>/}, and the payload template a spec
 * carries is the one the engine runs. Services once ran with most of their integrations
 * unspecified, which also hid two inline JSON payloads that could not escape a reviewer's free
 * text.
 */
@Tag("pack")
class ServiceSpecsTest {

  private static final Path PROCESSES =
      Path.of(System.getProperty("services.pack.dir", "../packs/test/engine"), "processes");
  private static final Path TEMPLATES =
      Path.of(System.getProperty("services.pack.dir", "../packs/test/engine"), "templates");
  private static final Path SPECS =
      Path.of(System.getProperty("services.docs.dir", "../packs/test/docs/business/services"));

  private static final String BPMN_NS = "http://www.omg.org/spec/BPMN/20100524/MODEL";
  private static final String CAMUNDA_NS = "http://camunda.org/schema/1.0/bpmn";
  private static final Pattern TASK_ID = Pattern.compile("\\*\\*BPMN task id:\\*\\* `([^`]+)`");
  private static final Pattern TEMPLATE = Pattern.compile("payload-template: (\\S+)");
  private static final Pattern FTL_BLOCK = Pattern.compile("```ftl\\n(.*?)\\n```", Pattern.DOTALL);

  static Stream<String> services() throws IOException {
    try (Stream<Path> dirs = Files.list(PROCESSES)) {
      List<String> names = dirs.map(p -> p.getFileName().toString()).sorted().toList();
      assertFalse(names.isEmpty(), "no services under " + PROCESSES);
      return names.stream();
    }
  }

  private static Document bpmn(String service) throws Exception {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setNamespaceAware(true);
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    return factory
        .newDocumentBuilder()
        .parse(PROCESSES.resolve(service).resolve(service + ".bpmn").toFile());
  }

  private static String read(Path path) throws IOException {
    return Files.readString(path).replace("\r\n", "\n");
  }

  /** The template resource each service task's payload uses, or null for none (a GET). */
  private static Map<String, String> payloadTemplates(Document doc) {
    Map<String, String> out = new HashMap<>();
    NodeList tasks = doc.getElementsByTagNameNS(BPMN_NS, "serviceTask");
    for (int i = 0; i < tasks.getLength(); i++) {
      Element task = (Element) tasks.item(i);
      String template = null;
      NodeList params = task.getElementsByTagNameNS(CAMUNDA_NS, "inputParameter");
      for (int j = 0; j < params.getLength(); j++) {
        Element param = (Element) params.item(j);
        if ("payload".equals(param.getAttribute("name"))) {
          NodeList scripts = param.getElementsByTagNameNS(CAMUNDA_NS, "script");
          template =
              scripts.getLength() > 0
                  ? ((Element) scripts.item(0)).getAttribute("resource")
                  : "inline:" + param.getTextContent();
        }
      }
      out.put(task.getAttribute("id"), template);
    }
    return out;
  }

  @ParameterizedTest
  @MethodSource("services")
  void everyServiceTaskHasOneSpecCarryingItsTemplate(String service) throws Exception {
    Map<String, String> tasks = payloadTemplates(bpmn(service));
    Map<String, Path> specs = new HashMap<>();
    Path dir = SPECS.resolve(service).resolve("service-tasks");
    if (Files.isDirectory(dir)) {
      try (Stream<Path> files = Files.list(dir)) {
        for (Path spec : files.filter(p -> p.toString().endsWith(".md")).toList()) {
          Matcher m = TASK_ID.matcher(read(spec));
          assertTrue(m.find(), spec + " names no BPMN task id");
          Path previous = specs.put(m.group(1), spec);
          assertEquals(null, previous, m.group(1) + " has two specs: " + previous + ", " + spec);
        }
      }
    }
    List<String> missing = new ArrayList<>(tasks.keySet());
    missing.removeAll(specs.keySet());
    assertTrue(missing.isEmpty(), service + ": service tasks without a spec: " + missing);
    List<String> stale = new ArrayList<>(specs.keySet());
    stale.removeAll(tasks.keySet());
    assertTrue(stale.isEmpty(), service + ": specs for tasks the BPMN does not have: " + stale);

    for (Map.Entry<String, Path> entry : specs.entrySet()) {
      String text = read(entry.getValue());
      String resource = tasks.get(entry.getKey());
      Matcher template = TEMPLATE.matcher(text);
      if (resource == null) {
        assertFalse(template.find(), entry.getValue() + " names a template the task does not use");
        continue;
      }
      assertTrue(template.find(), entry.getValue() + " names no payload-template");
      assertEquals(
          "templates/" + template.group(1), resource, entry.getValue() + ": BPMN uses another");
      Matcher body = FTL_BLOCK.matcher(text);
      assertTrue(body.find(), entry.getValue() + " carries no ```ftl template body");
      assertEquals(
          read(TEMPLATES.resolve(template.group(1))).stripTrailing(),
          body.group(1).stripTrailing(),
          entry.getValue() + ": the template body differs from " + resource);
    }
  }

  /**
   * JUEL cannot escape for JSON, so an inline payload with an expression breaks on a quote or a
   * line break in the value (docs/security.md, encode at every boundary).
   */
  @ParameterizedTest
  @MethodSource("services")
  void noPayloadIsInlineJsonWithExpressions(String service) throws Exception {
    for (Map.Entry<String, String> task : payloadTemplates(bpmn(service)).entrySet()) {
      String payload = task.getValue();
      assertFalse(
          payload != null && payload.startsWith("inline:") && payload.contains("${"),
          service + " " + task.getKey() + " builds JSON inline; use a ?json_string template");
    }
  }

  @ParameterizedTest
  @MethodSource("services")
  void everyDecisionHasASpec(String service) throws Exception {
    NodeList rules = bpmn(service).getElementsByTagNameNS(BPMN_NS, "businessRuleTask");
    for (int i = 0; i < rules.getLength(); i++) {
      String decision = ((Element) rules.item(i)).getAttributeNS(CAMUNDA_NS, "decisionRef");
      Path spec = SPECS.resolve(service).resolve("decisions").resolve(decision + ".md");
      assertTrue(Files.exists(spec), service + ": decision " + decision + " has no spec " + spec);
    }
  }
}
