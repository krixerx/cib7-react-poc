package com.poc.cib7.policy;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ProcessEngineException;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.TaskService;
import org.cibseven.bpm.engine.impl.identity.Authentication;
import org.cibseven.bpm.engine.task.Task;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.UriUtils;

/**
 * Enforces docs/security.md rule 2 on {@code /engine-rest}: a client may write only the variables
 * its form declares.
 *
 * <p>Engine authorization decides <em>whether</em> a user may complete a task or start a process;
 * it has no opinion on <em>which</em> variables come along. Without this filter an applicant
 * completing their own task could also set {@code decision}, {@code autoDecision} or any other
 * variable a gateway trusts. The filter therefore:
 *
 * <ul>
 *   <li>checks the {@code variables} of task completions ({@code complete}, {@code submit-form},
 *       {@code resolve}) against the allowlist of the task's {@code formKey}, and of process starts
 *       against the definition's start allowlist ({@link VariablePolicyRegistry});
 *   <li>lets {@code POST /task/{id}/localVariables} through under the same form allowlist, because
 *       the MCP {@code save_draft} tool stores drafts there;
 *   <li>closes every other endpoint that writes variables (instance and execution variables,
 *       messages, signals, conditions, modification, migration, restart, external tasks, CMMN) to
 *       anyone outside the administrator group.
 * </ul>
 *
 * <p>Members of the administrator group (people running the engine, and the backend's {@code
 * cib7-business} service account, which correlates messages and sets consent and payment state)
 * bypass the policy; engine authorization still applies to them.
 *
 * <p>Runs inside the {@code /engine-rest} security chain right after {@code
 * KeycloakAuthenticationFilter}, so the engine's {@link IdentityService} already carries the user
 * and groups. Task and definition lookups run as that user: a task the caller cannot see is never
 * described in the response, and a write to it is refused if it carries variables.
 */
public class VariableWritePolicyFilter extends OncePerRequestFilter {

  private static final Logger LOG = LoggerFactory.getLogger(VariableWritePolicyFilter.class);

  private static final ObjectMapper JSON = new ObjectMapper();

  static final String ENGINE_REST = "/engine-rest";

  private static final Pattern NAMED_ENGINE = Pattern.compile("^/engine/[^/]+(/.*)?$");

  private static final Pattern TASK_FORM =
      Pattern.compile("^/task/([^/]+)/(complete|submit-form|resolve)$");
  private static final Pattern TASK_DRAFT = Pattern.compile("^/task/([^/]+)/localVariables$");
  private static final Pattern START_BY_KEY =
      Pattern.compile("^/process-definition/key/([^/]+)(?:/tenant-id/[^/]+)?/(start|submit-form)$");
  private static final Pattern START_BY_ID =
      Pattern.compile("^/process-definition/([^/]+)/(start|submit-form)$");

  /**
   * Every other endpoint of the CIB seven 2.2 REST API whose request carries variables (found by
   * walking the {@code *RestService} interfaces of {@code cibseven-engine-rest-core-jakarta}).
   * Matched for any method except GET, HEAD and OPTIONS.
   */
  static final List<Pattern> ADMIN_ONLY =
      List.of(
          Pattern.compile("^/task/[^/]+/(variables|localVariables)(/.*)?$"),
          Pattern.compile("^/task/[^/]+/(bpmnError|bpmnEscalation)$"),
          Pattern.compile(
              "^/process-instance/[^/]+/(variables|modification|modification-async)(/.*)?$"),
          Pattern.compile("^/process-instance/(variables-async|message-async)$"),
          Pattern.compile("^/execution/[^/]+/(localVariables|signal|messageSubscriptions)(/.*)?$"),
          Pattern.compile("^/(message|signal|condition)$"),
          Pattern.compile("^/(modification|migration)/(execute|executeAsync)$"),
          Pattern.compile("^/process-definition/.+/(restart|restart-async)$"),
          Pattern.compile("^/external-task/[^/]+/(complete|failure|bpmnError)$"),
          Pattern.compile("^/case-(definition|execution|instance)/.+$"));

  private final VariablePolicyRegistry registry;
  private final IdentityService identityService;
  private final TaskService taskService;
  private final RepositoryService repositoryService;
  private final String adminGroup;

  public VariableWritePolicyFilter(
      VariablePolicyRegistry registry,
      IdentityService identityService,
      TaskService taskService,
      RepositoryService repositoryService,
      String adminGroup) {
    this.registry = registry;
    this.identityService = identityService;
    this.taskService = taskService;
    this.repositoryService = repositoryService;
    this.adminGroup = adminGroup;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    String method = request.getMethod().toUpperCase(Locale.ROOT);
    String path = enginePath(request);
    if (path == null || method.equals("GET") || method.equals("HEAD") || method.equals("OPTIONS")) {
      chain.doFilter(request, response);
      return;
    }
    if (isAdmin()) {
      chain.doFilter(request, response);
      return;
    }

    boolean post = method.equals("POST");
    Matcher m;
    if (post && (m = TASK_FORM.matcher(path)).matches()) {
      checkTask(request, response, chain, m.group(1), false);
    } else if (post && (m = TASK_DRAFT.matcher(path)).matches()) {
      checkTask(request, response, chain, m.group(1), true);
    } else if (post && (m = START_BY_KEY.matcher(path)).matches()) {
      checkStart(request, response, chain, m.group(1));
    } else if (post && (m = START_BY_ID.matcher(path)).matches()) {
      checkStart(request, response, chain, definitionKey(m.group(1)));
    } else if (ADMIN_ONLY.stream().anyMatch(p -> p.matcher(path).matches())) {
      deny(
          response,
          HttpServletResponse.SC_FORBIDDEN,
          method + " " + path + " writes process variables and is restricted to administrators");
    } else {
      chain.doFilter(request, response);
    }
  }

  private void checkTask(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain chain,
      String taskId,
      boolean draft)
      throws IOException, ServletException {
    byte[] body = request.getInputStream().readAllBytes();
    Set<String> written;
    try {
      JsonNode root = parse(body);
      written =
          draft
              ? union(objectKeys(root, "modifications"), stringArray(root, "deletions"))
              : objectKeys(root, "variables");
    } catch (InvalidBody e) {
      deny(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
      return;
    }
    if (!written.isEmpty()) {
      Optional<Task> task = task(taskId);
      String formKey = task.map(Task::getFormKey).orElse(null);
      String formName = formKey == null ? "this task" : VariablePolicy.formId(formKey);
      Optional<Set<String>> allowed =
          task.flatMap(t -> registry.forProcess(definitionKey(t.getProcessDefinitionId())))
              .flatMap(p -> p.form(formKey));
      Optional<String> refused = firstNotIn(written, allowed);
      if (refused.isPresent()) {
        deny(
            response,
            HttpServletResponse.SC_FORBIDDEN,
            "variable '" + refused.get() + "' is not writable from form '" + formName + "'");
        return;
      }
    }
    chain.doFilter(new CachedBodyRequest(request, body), response);
  }

  private void checkStart(
      HttpServletRequest request,
      HttpServletResponse response,
      FilterChain chain,
      String processDefinitionKey)
      throws IOException, ServletException {
    byte[] body = request.getInputStream().readAllBytes();
    Set<String> written;
    try {
      JsonNode root = parse(body);
      // Start instructions carry their own variables and can start the process anywhere but the
      // start event; the skip flags would bypass the identity and initiator listeners.
      if (isSet(root, "startInstructions")
          || isSet(root, "skipCustomListeners")
          || isSet(root, "skipIoMappings")) {
        deny(
            response,
            HttpServletResponse.SC_FORBIDDEN,
            "startInstructions, skipCustomListeners and skipIoMappings are restricted to"
                + " administrators");
        return;
      }
      written = objectKeys(root, "variables");
    } catch (InvalidBody e) {
      deny(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
      return;
    }
    if (!written.isEmpty()) {
      Optional<Set<String>> allowed =
          Optional.ofNullable(processDefinitionKey)
              .flatMap(registry::forProcess)
              .map(VariablePolicy::start);
      Optional<String> refused = firstNotIn(written, allowed);
      if (refused.isPresent()) {
        String name = processDefinitionKey == null ? "this process" : processDefinitionKey;
        deny(
            response,
            HttpServletResponse.SC_FORBIDDEN,
            "variable '" + refused.get() + "' is not writable when starting '" + name + "'");
        return;
      }
    }
    chain.doFilter(new CachedBodyRequest(request, body), response);
  }

  /** An absent allowlist (no policy, no form entry, unresolvable task) allows nothing. */
  private static Optional<String> firstNotIn(Set<String> written, Optional<Set<String>> allowed) {
    Set<String> allowlist = allowed.orElse(Set.of());
    return written.stream().filter(name -> !allowlist.contains(name)).findFirst();
  }

  private boolean isAdmin() {
    Authentication authentication = identityService.getCurrentAuthentication();
    return authentication != null
        && authentication.getGroupIds() != null
        && authentication.getGroupIds().contains(adminGroup);
  }

  /** Looked up as the caller, so a task they cannot see stays invisible. */
  private Optional<Task> task(String taskId) {
    try {
      return Optional.ofNullable(
          taskService.createTaskQuery().taskId(taskId).initializeFormKeys().singleResult());
    } catch (ProcessEngineException e) {
      return Optional.empty();
    }
  }

  private String definitionKey(String processDefinitionId) {
    if (processDefinitionId == null) {
      return null;
    }
    try {
      return repositoryService.getProcessDefinition(processDefinitionId).getKey();
    } catch (ProcessEngineException e) {
      return null;
    }
  }

  /**
   * The path below {@code /engine-rest}, decoded the way JAX-RS decodes it for matching, without
   * the optional {@code /engine/{name}} prefix and trailing slashes. {@code null} outside the REST
   * API.
   */
  static String enginePath(HttpServletRequest request) {
    String uri = request.getRequestURI();
    String contextPath = request.getContextPath();
    if (contextPath != null && uri.startsWith(contextPath)) {
      uri = uri.substring(contextPath.length());
    }
    String path = UriUtils.decode(uri, StandardCharsets.UTF_8);
    if (!path.equals(ENGINE_REST) && !path.startsWith(ENGINE_REST + "/")) {
      return null;
    }
    path = path.substring(ENGINE_REST.length());
    Matcher named = NAMED_ENGINE.matcher(path);
    if (named.matches()) {
      path = named.group(1) == null ? "" : named.group(1);
    }
    while (path.length() > 1 && path.endsWith("/")) {
      path = path.substring(0, path.length() - 1);
    }
    return path;
  }

  private static JsonNode parse(byte[] body) throws InvalidBody {
    if (new String(body, StandardCharsets.UTF_8).isBlank()) {
      return JSON.createObjectNode();
    }
    try {
      JsonNode root = JSON.readTree(body);
      if (root == null || root.isNull()) {
        return JSON.createObjectNode();
      }
      if (!root.isObject()) {
        throw new InvalidBody("request body must be a JSON object");
      }
      return root;
    } catch (JsonProcessingException e) {
      throw new InvalidBody("request body is not valid JSON");
    } catch (IOException e) {
      throw new InvalidBody("request body could not be read");
    }
  }

  /**
   * The values of every top-level field whose name matches ignoring case, so a lenient deserializer
   * cannot be handed a spelling the check did not look at.
   */
  private static List<JsonNode> fields(JsonNode root, String name) {
    List<JsonNode> values = new ArrayList<>();
    Iterator<Map.Entry<String, JsonNode>> it = root.fields();
    while (it.hasNext()) {
      Map.Entry<String, JsonNode> entry = it.next();
      if (entry.getKey().equalsIgnoreCase(name) && !entry.getValue().isNull()) {
        values.add(entry.getValue());
      }
    }
    return values;
  }

  /** True unless every spelling of the field is absent, false or an empty array. */
  private static boolean isSet(JsonNode root, String name) {
    for (JsonNode value : fields(root, name)) {
      boolean unset =
          (value.isBoolean() && !value.asBoolean()) || (value.isArray() && value.isEmpty());
      if (!unset) {
        return true;
      }
    }
    return false;
  }

  private static Set<String> objectKeys(JsonNode root, String name) throws InvalidBody {
    Set<String> keys = new LinkedHashSet<>();
    for (JsonNode value : fields(root, name)) {
      if (!value.isObject()) {
        throw new InvalidBody("'" + name + "' must be a JSON object");
      }
      value.fieldNames().forEachRemaining(keys::add);
    }
    return keys;
  }

  private static Set<String> stringArray(JsonNode root, String name) throws InvalidBody {
    Set<String> values = new LinkedHashSet<>();
    for (JsonNode value : fields(root, name)) {
      if (!value.isArray()) {
        throw new InvalidBody("'" + name + "' must be a JSON array");
      }
      for (JsonNode element : value) {
        values.add(element.asText());
      }
    }
    return values;
  }

  private static Set<String> union(Set<String> a, Set<String> b) {
    Set<String> all = new LinkedHashSet<>(a);
    all.addAll(b);
    return all;
  }

  private void deny(HttpServletResponse response, int status, String message) throws IOException {
    Authentication authentication = identityService.getCurrentAuthentication();
    LOG.warn(
        "Refused engine write by '{}': {}",
        authentication == null ? null : authentication.getUserId(),
        message);
    response.setStatus(status);
    response.setContentType("application/json");
    response.setCharacterEncoding(StandardCharsets.UTF_8.name());
    response
        .getWriter()
        .write(
            JSON.writeValueAsString(Map.of("type", "VariablePolicyViolation", "message", message)));
  }

  private static final class InvalidBody extends Exception {
    InvalidBody(String message) {
      super(message);
    }
  }
}
