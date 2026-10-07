package com.poc.cib7.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.cibseven.bpm.dmn.engine.DmnDecision;
import org.cibseven.bpm.dmn.engine.DmnDecisionTableResult;
import org.cibseven.bpm.dmn.engine.DmnEngine;
import org.cibseven.bpm.dmn.engine.DmnEngineConfiguration;
import org.cibseven.bpm.engine.variable.VariableMap;
import org.cibseven.bpm.engine.variable.Variables;
import org.cibseven.bpm.model.dmn.Dmn;
import org.cibseven.bpm.model.dmn.DmnModelInstance;
import org.cibseven.bpm.model.xml.instance.ModelElementInstance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Runs every decision spec's examples against the pack's DMN (docs/platform-api.md, "Service
 * examples"): a decision's expected behaviour is pack data, so it travels with the pack and holds
 * for any pack.
 *
 * <p>A spec {@code decisions/<id>.md} names its {@code **Decision id:**} and {@code **Output
 * variable:**} and has an {@code ## Examples} table: one column per input variable, one for the
 * output, values as JSON literals in backticks ({@code `30`}, {@code `3000.0`}, {@code "citizen"},
 * {@code null}). An {@code ## Examples without `<rule id>`} table is evaluated with that rule
 * removed, for a rule that switches the real policy off (a demo rule). Each row must match exactly
 * one rule.
 */
@Tag("pack")
class DecisionExamplesTest {

  private static final Path ENGINE =
      Path.of(System.getProperty("services.pack.dir", "../packs/test/engine"));
  private static final Path SPECS =
      Path.of(System.getProperty("services.docs.dir", "../packs/test/docs/business/services"));
  private static final String WITHOUT = "Examples without ";

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final DmnEngine DMN =
      DmnEngineConfiguration.createDefaultDmnEngineConfiguration().buildEngine();

  @Test
  void everyDecisionBehavesAsItsSpecsExamplesSay() throws IOException {
    List<String> problems = new ArrayList<>();
    int checked = 0;
    for (Path spec : decisionSpecs()) {
      String service = spec.getParent().getParent().getFileName().toString();
      String at = service + "/decisions/" + spec.getFileName();
      String md = Files.readString(spec);
      String decisionId = SpecTables.field(md, "Decision id").orElse(null);
      String output = SpecTables.field(md, "Output variable").orElse(null);
      if (decisionId == null || output == null) {
        problems.add(at + ": needs **Decision id:** and **Output variable:**");
        continue;
      }
      Path dmn = dmnFile(service, decisionId);
      if (dmn == null) {
        problems.add(at + ": no DMN in processes/" + service + " defines " + decisionId);
        continue;
      }
      SpecTables.Table examples = SpecTables.under(md, "Examples").orElse(null);
      if (examples == null || examples.rows().isEmpty()) {
        problems.add(at + ": has no ## Examples table");
        continue;
      }
      checked += run(at, parse(dmn, decisionId, null), output, examples, problems);
      for (String heading : SpecTables.headingsStartingWith(md, WITHOUT)) {
        String rule = SpecTables.unquote(heading.substring(WITHOUT.length()));
        SpecTables.Table table = SpecTables.under(md, heading).orElseThrow();
        checked +=
            run(at + " (" + heading + ")", parse(dmn, decisionId, rule), output, table, problems);
      }
    }
    assertEquals(List.of(), problems);
    assertTrue(checked > 0 || decisionSpecs().isEmpty(), "no decision examples ran");
  }

  private static int run(
      String at,
      DmnDecision decision,
      String output,
      SpecTables.Table table,
      List<String> problems) {
    if (!table.header().contains(output)) {
      problems.add(at + ": the examples have no '" + output + "' column");
      return 0;
    }
    int row = 0;
    for (Map<String, String> example : table.rows()) {
      row++;
      VariableMap variables = Variables.createVariables();
      try {
        for (String column : table.header()) {
          if (!column.equals(output)) {
            variables.putValue(column, value(example.get(column)));
          }
        }
        Object expected = value(example.get(output));
        DmnDecisionTableResult result = DMN.evaluateDecisionTable(decision, variables);
        if (result.size() != 1) {
          problems.add(at + " row " + row + ": " + result.size() + " rules matched, expected 1");
          continue;
        }
        Object actual = result.getSingleEntry();
        if (!String.valueOf(expected).equals(String.valueOf(actual))) {
          problems.add(
              at + " row " + row + " " + variables + ": " + actual + ", expected " + expected);
        }
      } catch (IOException e) {
        problems.add(at + " row " + row + ": a value is no JSON literal (" + e.getMessage() + ")");
      }
    }
    return row;
  }

  /** A cell as a JSON literal: numbers keep integer or double, strings and null as they are. */
  private static Object value(String cell) throws IOException {
    JsonNode node = JSON.readTree(SpecTables.unquote(cell));
    if (node == null || node.isNull()) {
      return null;
    }
    if (node.isIntegralNumber()) {
      return node.canConvertToInt() ? node.intValue() : node.longValue();
    }
    if (node.isNumber()) {
      return node.doubleValue();
    }
    if (node.isBoolean()) {
      return node.booleanValue();
    }
    if (node.isTextual()) {
      return node.textValue();
    }
    throw new IOException(cell);
  }

  private static DmnDecision parse(Path dmn, String decisionId, String withoutRule)
      throws IOException {
    try (InputStream in = Files.newInputStream(dmn)) {
      DmnModelInstance model = Dmn.readModelFromStream(in);
      if (withoutRule != null) {
        ModelElementInstance rule = model.getModelElementById(withoutRule);
        if (rule == null) {
          throw new IllegalStateException(dmn.getFileName() + " has no rule " + withoutRule);
        }
        rule.getParentElement().removeChildElement(rule);
      }
      return DMN.parseDecision(decisionId, model);
    }
  }

  private static Path dmnFile(String service, String decisionId) throws IOException {
    Path dir = ENGINE.resolve("processes").resolve(service);
    if (!Files.isDirectory(dir)) {
      return null;
    }
    try (Stream<Path> files = Files.list(dir)) {
      for (Path f : files.filter(p -> p.toString().endsWith(".dmn")).toList()) {
        if (Files.readString(f).contains("id=\"" + decisionId + "\"")) {
          return f;
        }
      }
    }
    return null;
  }

  private static List<Path> decisionSpecs() throws IOException {
    if (!Files.isDirectory(SPECS)) {
      return List.of();
    }
    try (Stream<Path> walk = Files.walk(SPECS)) {
      return walk.filter(
              p ->
                  p.getParent() != null
                      && p.getParent().getFileName().toString().equals("decisions")
                      && p.toString().endsWith(".md"))
          .sorted()
          .toList();
    }
  }
}
