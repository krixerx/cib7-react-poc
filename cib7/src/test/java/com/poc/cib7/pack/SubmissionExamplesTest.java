package com.poc.cib7.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.Schema;
import com.poc.cib7.policy.FormSchemaRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Runs every form spec's submission examples against the form's value schema, the one the engine
 * checks every completion with (docs/platform-api.md, "Service examples"). The rules a form's
 * values must keep are pack data, tested with the pack.
 *
 * <p>A spec {@code forms/<id>.md} whose form has a schema has a {@code ## Submission examples}
 * section: a valid submission in a {@code json} code block, then a table {@code | Change | Result |
 * Why |}. A change is a JSON object whose fields replace the submission's (a whole list or object
 * at a time); the result is {@code accepted} or {@code refused}.
 */
@Tag("pack")
class SubmissionExamplesTest {

  private static final Path ENGINE =
      Path.of(System.getProperty("services.pack.dir", "../packs/test/engine"));
  private static final Path SPECS =
      Path.of(System.getProperty("services.docs.dir", "../packs/test/docs/business/services"));
  private static final String HEADING = "Submission examples";
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void everyFormKeepsTheRulesItsSpecsExamplesSay() throws IOException {
    FormSchemaRegistry schemas =
        new FormSchemaRegistry(new String[] {"classpath*:processes/*/schemas/*.json"});
    List<String> problems = new ArrayList<>();
    try (var services = Files.list(ENGINE.resolve("processes"))) {
      for (Path service : services.filter(Files::isDirectory).sorted().toList()) {
        Path policyFile = service.resolve("variable-policy.json");
        if (!Files.exists(policyFile)) {
          continue;
        }
        JsonNode policy = JSON.readTree(policyFile.toFile());
        String process = policy.path("processDefinitionKey").asText();
        for (String form : (Iterable<String>) () -> policy.path("forms").fieldNames()) {
          Schema schema = schemas.forForm(process, form).orElse(null);
          if (schema == null) {
            continue;
          }
          Path spec = SPECS.resolve(service.getFileName()).resolve("forms").resolve(form + ".md");
          String at = service.getFileName() + "/forms/" + form + ".md";
          if (!Files.exists(spec)) {
            problems.add(at + ": missing");
            continue;
          }
          check(at, Files.readString(spec), schema, problems);
        }
      }
    }
    assertEquals(List.of(), problems);
  }

  private static void check(String at, String md, Schema schema, List<String> problems) {
    String base = SpecTables.codeBlock(md, HEADING).orElse(null);
    SpecTables.Table table = SpecTables.under(md, HEADING).orElse(null);
    if (base == null || table == null || table.rows().isEmpty()) {
      problems.add(at + ": needs a ## " + HEADING + " section (a json block and a table)");
      return;
    }
    ObjectNode submission;
    try {
      submission = (ObjectNode) JSON.readTree(base);
    } catch (IOException | ClassCastException e) {
      problems.add(at + ": the example submission is no JSON object");
      return;
    }
    int row = 0;
    for (Map<String, String> example : table.rows()) {
      row++;
      String result = SpecTables.unquote(example.getOrDefault("Result", "")).strip();
      ObjectNode values = submission.deepCopy();
      try {
        JsonNode change = JSON.readTree(SpecTables.unquote(example.getOrDefault("Change", "{}")));
        change.fields().forEachRemaining(e -> values.set(e.getKey(), e.getValue()));
      } catch (IOException e) {
        problems.add(at + " row " + row + ": the change is no JSON object");
        continue;
      }
      List<String> violations = FormSchemaRegistry.violations(schema, values);
      boolean accepted = violations.isEmpty();
      if (!result.equals("accepted") && !result.equals("refused")) {
        problems.add(at + " row " + row + ": result must be accepted or refused");
      } else if (accepted != result.equals("accepted")) {
        problems.add(
            at
                + " row "
                + row
                + " ("
                + example.getOrDefault("Why", "")
                + "): "
                + (accepted ? "accepted" : "refused " + violations)
                + ", expected "
                + result);
      }
    }
  }
}
