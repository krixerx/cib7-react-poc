package com.poc.backend.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Thin typed wrapper over the engine's {@code /engine-rest} API — the only channel this
 * microservice uses to talk to the CIB seven engine. Replaces the embedded Java API ({@code
 * RuntimeService} / {@code RepositoryService}) the controllers used while they still lived inside
 * the engine module.
 *
 * <p>Mapping from the old Java API to REST:
 *
 * <ul>
 *   <li>{@code createProcessInstanceQuery().variableValueEquals(...)} → {@code POST
 *       /process-instance} with a {@code variables} filter
 *   <li>{@code getVariable(...)} → {@code GET /process-instance/{id}/variables/{name}
 *       ?deserializeValue=false}; Spin JSON variables arrive as type {@code Json} with the raw JSON
 *       document in {@code value}, parsed here with Jackson (no Spin dependency)
 *   <li>{@code setVariable(...)} → {@code PUT .../variables/{name}} with the matching engine type
 *       ({@code Json} round-trips as a Spin JSON value, so BPMN expressions like {@code .prop(...)}
 *       keep working)
 *   <li>{@code createMessageCorrelation(...).localVariableEquals(...)} → {@code POST /message} with
 *       {@code localCorrelationKeys}
 * </ul>
 */
@Component
public class EngineClient {

  private final RestClient rest;
  private final ObjectMapper mapper = new ObjectMapper();

  public EngineClient(RestClient engineRestClient) {
    this.rest = engineRestClient;
  }

  // --- process instance queries -----------------------------------------

  /** Active instance summary: id + the engine's definitionKey. */
  public record ProcessInstanceRef(String id, String definitionKey) {}

  /**
   * Active instances of {@code definitionKey} where the process variable {@code variableName}
   * equals {@code value}. The engine indexes variable equality, so this is the fast path for token
   * lookup.
   */
  public List<String> findActiveByVariable(
      String definitionKey, String variableName, String value) {
    Map<String, Object> body =
        Map.of(
            "processDefinitionKey",
            definitionKey,
            "active",
            true,
            "variables",
            List.of(
                Map.of(
                    "name", variableName,
                    "operator", "eq",
                    "value", value)));
    return queryInstanceIds(body);
  }

  /** All active instances of {@code definitionKey}. */
  public List<String> findActive(String definitionKey) {
    return queryInstanceIds(Map.of("processDefinitionKey", definitionKey, "active", true));
  }

  /**
   * The active instance with this exact id, or {@code null} when the id is unknown, ended, or
   * suspended — mirrors the old {@code processInstanceQuery().processInstanceId(id).active()}.
   */
  public ProcessInstanceRef findActiveById(String processInstanceId) {
    try {
      JsonNode pi =
          rest.get()
              .uri("/process-instance/{id}", processInstanceId)
              .retrieve()
              .body(JsonNode.class);
      if (pi == null || pi.path("suspended").asBoolean(false)) {
        return null;
      }
      // Use the definitionKey the engine already returns. CIB seven issues
      // bare-UUID definition ids (no key:version:deployment prefix), so the old
      // approach of splitting definitionId on ':' kept the whole UUID as the
      // "key" and every downstream key comparison (e.g. PaymentController)
      // silently missed. Fall back to the definitionId prefix only if the
      // definitionKey field is somehow absent.
      String key = pi.path("definitionKey").asText("");
      if (key.isBlank()) {
        String definitionId = pi.path("definitionId").asText("");
        key =
            definitionId.contains(":")
                ? definitionId.substring(0, definitionId.indexOf(':'))
                : definitionId;
      }
      return new ProcessInstanceRef(pi.path("id").asText(), key);
    } catch (HttpClientErrorException.NotFound e) {
      return null;
    }
  }

  /**
   * {@code startUserId} of the (possibly already ended) process instance from history, or {@code
   * null} when the instance is unknown or was started without an authenticated user. Drives the
   * documents API's per-case access rule.
   */
  public String getHistoricStartUserId(String processInstanceId) {
    try {
      JsonNode pi =
          rest.get()
              .uri("/history/process-instance/{id}", processInstanceId)
              .retrieve()
              .body(JsonNode.class);
      if (pi == null || pi.path("startUserId").isNull()) {
        return null;
      }
      return pi.path("startUserId").asText();
    } catch (HttpClientErrorException.NotFound e) {
      return null;
    }
  }

  /**
   * Whether the engine's history knows this process instance (running or ended). Reviewers may see
   * any case, but only cases that exist: without this check an unknown id would answer like a real
   * one and let a reviewer stage objects under an invented {@code process/<id>/} prefix.
   */
  public boolean historicProcessInstanceExists(String processInstanceId) {
    try {
      JsonNode pi =
          rest.get()
              .uri("/history/process-instance/{id}", processInstanceId)
              .retrieve()
              .body(JsonNode.class);
      return pi != null && !pi.path("id").asText("").isEmpty();
    } catch (HttpClientErrorException.NotFound e) {
      return false;
    }
  }

  /** Ids of the unfinished top-level cases {@code userId} started, newest first. */
  public List<String> unfinishedInstanceIdsStartedBy(String userId) {
    JsonNode result =
        rest.get()
            .uri(
                "/history/process-instance?startedBy={user}&unfinished=true"
                    + "&rootProcessInstances=true&sortBy=startTime&sortOrder=desc&maxResults=100",
                userId)
            .retrieve()
            .body(JsonNode.class);
    return toList(result).stream().map(pi -> pi.path("id").asText()).toList();
  }

  /**
   * How many user tasks of this case have been completed. Zero means the applicant has not
   * submitted the first form yet, which is what makes a case a draft: three of the four services
   * loop back to their first task on send-back, so "sitting at the first task" is not enough.
   */
  public long finishedTaskCount(String processInstanceId) {
    JsonNode result =
        rest.get()
            .uri("/history/task/count?processInstanceId={id}&finished=true", processInstanceId)
            .retrieve()
            .body(JsonNode.class);
    return result == null ? 0 : result.path("count").asLong(0);
  }

  /**
   * Removes a case from the runtime and from history, so it disappears from every list instead of
   * showing up as an ended case. Listeners and I/O mappings are skipped: nothing a draft could have
   * triggered needs undoing.
   */
  public void deleteProcessInstance(String processInstanceId) {
    rest.delete()
        .uri(
            "/process-instance/{id}?skipCustomListeners=true&skipIoMappings=true",
            processInstanceId)
        .retrieve()
        .toBodilessEntity();
    rest.delete()
        .uri("/history/process-instance/{id}", processInstanceId)
        .retrieve()
        .toBodilessEntity();
  }

  private List<String> queryInstanceIds(Map<String, Object> queryBody) {
    JsonNode result =
        rest.post().uri("/process-instance").body(queryBody).retrieve().body(JsonNode.class);
    List<String> ids = new ArrayList<>();
    if (result != null && result.isArray()) {
      result.forEach(pi -> ids.add(pi.path("id").asText()));
    }
    return ids;
  }

  // --- statistics ---------------------------------------------------------

  private static final int PAGE_SIZE = 1000;

  /** Latest version of every deployed process definition. */
  public List<JsonNode> latestProcessDefinitions() {
    JsonNode result =
        rest.get().uri("/process-definition?latestVersion=true").retrieve().body(JsonNode.class);
    return toList(result);
  }

  /**
   * Historic top-level process instances matching {@code query} (a {@code POST
   * /history/process-instance} body), paged through until exhausted or {@code limit} rows are read.
   * Callers pass {@code limit + 1} to detect truncation.
   */
  public List<JsonNode> historicProcessInstances(Map<String, Object> query, int limit) {
    Map<String, Object> body = new LinkedHashMap<>(query);
    body.put("rootProcessInstances", true);
    body.put(
        "sorting",
        List.of(
            Map.of("sortBy", "startTime", "sortOrder", "asc"),
            Map.of("sortBy", "instanceId", "sortOrder", "asc")));
    return pagedPost("/history/process-instance", body, limit);
  }

  /** Historic activity instances started at or after {@code startedAfter}, any process. */
  public List<JsonNode> historicActivityInstancesStartedAfter(String startedAfter, int limit) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("startedAfter", startedAfter);
    body.put(
        "sorting",
        List.of(
            Map.of("sortBy", "startTime", "sortOrder", "asc"),
            Map.of("sortBy", "activityInstanceId", "sortOrder", "asc")));
    return pagedPost("/history/activity-instance", body, limit);
  }

  /** Every incident that is still open, including the copies propagated to parent instances. */
  public List<JsonNode> openIncidents() {
    JsonNode result = rest.get().uri("/history/incident?open=true").retrieve().body(JsonNode.class);
    return toList(result);
  }

  private List<JsonNode> pagedPost(String path, Map<String, Object> body, int limit) {
    List<JsonNode> rows = new ArrayList<>();
    while (rows.size() < limit) {
      int first = rows.size();
      int max = Math.min(PAGE_SIZE, limit - first);
      JsonNode page =
          rest.post()
              .uri(path + "?firstResult={first}&maxResults={max}", first, max)
              .body(body)
              .retrieve()
              .body(JsonNode.class);
      List<JsonNode> pageRows = toList(page);
      rows.addAll(pageRows);
      if (pageRows.size() < max) {
        break;
      }
    }
    return rows;
  }

  private static List<JsonNode> toList(JsonNode array) {
    List<JsonNode> rows = new ArrayList<>();
    if (array != null && array.isArray()) {
      array.forEach(rows::add);
    }
    return rows;
  }

  // --- variables ---------------------------------------------------------

  public String getStringVariable(String processInstanceId, String name) {
    JsonNode v = getVariable(processInstanceId, name);
    return v == null || v.path("value").isNull() ? null : v.path("value").asText();
  }

  public Boolean getBooleanVariable(String processInstanceId, String name) {
    JsonNode v = getVariable(processInstanceId, name);
    if (v == null || !v.path("value").isBoolean()) return null;
    return v.path("value").asBoolean();
  }

  /**
   * The raw {@code value} field, whatever JSON type the engine reported — for variables like {@code
   * price} that may surface as a number or a locale-formatted string depending on which path wrote
   * them.
   */
  public Object getRawVariable(String processInstanceId, String name) {
    JsonNode v = getVariable(processInstanceId, name);
    if (v == null) return null;
    JsonNode value = v.path("value");
    if (value.isNumber()) return value.numberValue();
    if (value.isTextual()) return value.textValue();
    if (value.isBoolean()) return value.booleanValue();
    return null;
  }

  /**
   * A Spin JSON ({@code type=Json}) variable parsed into a Jackson tree, or {@code null} when the
   * variable is missing or null. With {@code deserializeValue=false} the engine sends the raw JSON
   * document as a string in {@code value}.
   */
  public JsonNode getJsonVariable(String processInstanceId, String name) {
    JsonNode v = getVariable(processInstanceId, name);
    if (v == null || v.path("value").isNull()) return null;
    try {
      return mapper.readTree(v.path("value").asText());
    } catch (tools.jackson.core.JacksonException e) {
      throw new IllegalStateException(
          "Variable " + name + " on PI " + processInstanceId + " is not valid JSON", e);
    }
  }

  private JsonNode getVariable(String processInstanceId, String name) {
    try {
      return rest.get()
          .uri(
              "/process-instance/{id}/variables/{name}?deserializeValue=false",
              processInstanceId,
              name)
          .retrieve()
          .body(JsonNode.class);
    } catch (HttpClientErrorException.NotFound e) {
      return null;
    }
  }

  public void setJsonVariable(String processInstanceId, String name, JsonNode value) {
    putVariable(processInstanceId, name, Map.of("value", value.toString(), "type", "Json"));
  }

  public void setBooleanVariable(String processInstanceId, String name, boolean value) {
    putVariable(processInstanceId, name, Map.of("value", value, "type", "Boolean"));
  }

  public void setStringVariable(String processInstanceId, String name, String value) {
    putVariable(processInstanceId, name, Map.of("value", value, "type", "String"));
  }

  private void putVariable(String processInstanceId, String name, Map<String, Object> body) {
    rest.put()
        .uri("/process-instance/{id}/variables/{name}", processInstanceId, name)
        .body(body)
        .retrieve()
        .toBodilessEntity();
  }

  // --- message correlation ------------------------------------------------

  /**
   * Correlates {@code messageName} against the given process instance.
   *
   * @param localCorrelationKeys matched against execution-local variables — identifies WHICH
   *     waiting receive task inside a multi-instance subprocess is unblocked (the old {@code
   *     localVariableEquals})
   * @param processVariables set at process scope as part of the correlation
   * @return {@code false} when no execution is waiting for the message (the REST equivalent of
   *     {@code MismatchingMessageCorrelationException}, which the engine maps to 400)
   */
  public boolean correlateMessage(
      String messageName,
      String processInstanceId,
      Map<String, String> localCorrelationKeys,
      Map<String, Object> processVariables) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("messageName", messageName);
    body.put("processInstanceId", processInstanceId);
    if (localCorrelationKeys != null && !localCorrelationKeys.isEmpty()) {
      Map<String, Object> keys = new LinkedHashMap<>();
      localCorrelationKeys.forEach((k, v) -> keys.put(k, Map.of("value", v, "type", "String")));
      body.put("localCorrelationKeys", keys);
    }
    if (processVariables != null && !processVariables.isEmpty()) {
      Map<String, Object> vars = new LinkedHashMap<>();
      processVariables.forEach((k, v) -> vars.put(k, typedValue(v)));
      body.put("processVariables", vars);
    }
    try {
      rest.post().uri("/message").body(body).retrieve().toBodilessEntity();
      return true;
    } catch (HttpClientErrorException.BadRequest e) {
      return false;
    }
  }

  private static Map<String, Object> typedValue(Object v) {
    if (v instanceof Boolean b) return Map.of("value", b, "type", "Boolean");
    if (v instanceof Number n) return Map.of("value", n, "type", "Double");
    return Map.of("value", String.valueOf(v), "type", "String");
  }
}
