package com.poc.cib7.pack;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.poc.cib7.keycloak.KeycloakIdentityProvider;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.runtime.ActivityInstance;
import org.cibseven.bpm.engine.runtime.ProcessInstance;
import org.cibseven.bpm.engine.task.Task;
import org.cibseven.spin.Spin;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Runs every service's flow scenarios on the pack's deployed processes (docs/platform-api.md,
 * "Service examples"): how a case moves through its process is pack data, tested with the pack.
 *
 * <p>A service README may have a {@code ## Flow scenarios} section with one {@code ### Scenario:
 * <name>} per case, each a {@code json} code block: {@code start} (the start variables) and an
 * ordered list of {@code steps}, each one of
 *
 * <ul>
 *   <li>{@code {"expectTask": "<task id>"}}: that user task is open;
 *   <li>{@code {"complete": "<task id>", "variables": {...}}}: completes it, an object or a list
 *       becoming a Spin JSON variable as the engine stores the forms' Json values;
 *   <li>{@code {"expectVariables": {...}}}: the case's variables have these values;
 *   <li>{@code {"expectWaitingAt": "<activity id>"}}: the case waits there, at a user task, a
 *       receive task or an asynchronous step.
 * </ul>
 *
 * The job executor is off, so the case stops at the first asynchronous step and no connector calls
 * out: a scenario covers the engine's routing (gateways, decisions, listeners), not the
 * integrations, which the template examples cover.
 */
@Tag("pack")
@SpringBootTest(properties = "camunda.bpm.job-execution.enabled=false")
@ActiveProfiles("test")
class FlowScenariosTest {

  private static final Path ENGINE =
      Path.of(System.getProperty("services.pack.dir", "../packs/test/engine"));
  private static final Path SPECS =
      Path.of(System.getProperty("services.docs.dir", "../packs/test/docs/business/services"));
  private static final String PREFIX = "Scenario: ";
  private static final ObjectMapper JSON = new ObjectMapper();

  @MockitoBean private KeycloakIdentityProvider keycloakIdentityProvider;

  @Autowired private ProcessEngine engine;

  @Test
  void everyServiceMovesAsItsSpecsScenariosSay() throws IOException {
    List<String> problems = new ArrayList<>();
    for (Path readme : readmes()) {
      String service = readme.getParent().getFileName().toString();
      String md = Files.readString(readme);
      for (String heading : SpecTables.headingsStartingWith(md, PREFIX)) {
        String at = service + " (" + heading + ")";
        try {
          JsonNode scenario = JSON.readTree(SpecTables.codeBlock(md, heading).orElse(""));
          run(at, processKey(service), scenario, problems);
        } catch (IOException e) {
          problems.add(at + ": the scenario is no JSON (" + e.getMessage() + ")");
        } catch (RuntimeException e) {
          problems.add(at + ": " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }
      }
    }
    assertEquals(List.of(), problems);
  }

  private void run(String at, String processKey, JsonNode scenario, List<String> problems) {
    ProcessInstance pi =
        engine
            .getRuntimeService()
            .startProcessInstanceByKey(processKey, variables(scenario.path("start")));
    int n = 0;
    for (JsonNode step : scenario.path("steps")) {
      n++;
      String where = at + " step " + n;
      if (step.has("expectTask")) {
        String id = step.get("expectTask").asText();
        if (openTask(pi, id) == null) {
          problems.add(where + ": no open task " + id);
          return;
        }
      } else if (step.has("complete")) {
        String id = step.get("complete").asText();
        Task task = openTask(pi, id);
        if (task == null) {
          problems.add(where + ": no open task " + id + " to complete");
          return;
        }
        engine.getTaskService().complete(task.getId(), variables(step.path("variables")));
      } else if (step.has("expectVariables")) {
        Map<String, Object> actual = engine.getRuntimeService().getVariables(pi.getId());
        step.get("expectVariables")
            .fields()
            .forEachRemaining(
                e -> {
                  Object value = actual.get(e.getKey());
                  if (!same(e.getValue(), value)) {
                    problems.add(
                        where + ": " + e.getKey() + " is " + value + ", expected " + e.getValue());
                  }
                });
      } else if (step.has("expectWaitingAt")) {
        String id = step.get("expectWaitingAt").asText();
        ActivityInstance tree = engine.getRuntimeService().getActivityInstance(pi.getId());
        if (tree == null
            || (tree.getActivityInstances(id).length == 0
                && tree.getTransitionInstances(id).length == 0)) {
          problems.add(where + ": the case does not wait at " + id);
          return;
        }
      } else {
        problems.add(where + ": unknown step " + step);
        return;
      }
    }
  }

  private Task openTask(ProcessInstance pi, String taskId) {
    List<Task> tasks =
        engine
            .getTaskService()
            .createTaskQuery()
            .processInstanceId(pi.getId())
            .taskDefinitionKey(taskId)
            .list();
    return tasks.size() == 1 ? tasks.get(0) : null;
  }

  /** A JSON literal against an engine value: numbers by value, everything else by text. */
  private static boolean same(JsonNode expected, Object actual) {
    if (expected.isNull()) {
      return actual == null;
    }
    if (actual == null) {
      return false;
    }
    if (expected.isNumber() && actual instanceof Number number) {
      return expected.decimalValue().compareTo(new java.math.BigDecimal(number.toString())) == 0;
    }
    return expected.isTextual()
        ? expected.textValue().equals(String.valueOf(actual))
        : expected.toString().equals(String.valueOf(actual).replace(" ", ""));
  }

  private static Map<String, Object> variables(JsonNode node) {
    Map<String, Object> out = new HashMap<>();
    for (Iterator<Map.Entry<String, JsonNode>> it = node.fields(); it.hasNext(); ) {
      Map.Entry<String, JsonNode> e = it.next();
      JsonNode v = e.getValue();
      Object value;
      if (v.isObject() || v.isArray()) {
        value = Spin.JSON(v.toString());
      } else if (v.isNull()) {
        value = null;
      } else if (v.isIntegralNumber()) {
        value = v.canConvertToInt() ? (Object) v.intValue() : (Object) v.longValue();
      } else if (v.isNumber()) {
        value = v.doubleValue();
      } else if (v.isBoolean()) {
        value = v.booleanValue();
      } else {
        value = v.asText();
      }
      out.put(e.getKey(), value);
    }
    return out;
  }

  /** A service's process key, from its variable policy. */
  private static String processKey(String service) throws IOException {
    Path policy = ENGINE.resolve("processes").resolve(service).resolve("variable-policy.json");
    return JSON.readTree(policy.toFile()).path("processDefinitionKey").asText();
  }

  private static List<Path> readmes() throws IOException {
    if (!Files.isDirectory(SPECS)) {
      return List.of();
    }
    try (Stream<Path> dirs = Files.list(SPECS)) {
      return dirs.map(d -> d.resolve("README.md")).filter(Files::exists).sorted().toList();
    }
  }
}
