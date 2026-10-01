package com.poc.cib7.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.poc.cib7.keycloak.KeycloakIdentityProvider;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.ProcessEngine;
import org.cibseven.bpm.engine.identity.Group;
import org.cibseven.bpm.engine.identity.User;
import org.cibseven.bpm.engine.task.Task;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Negative tests for docs/security.md rule 2, end to end through the real {@code /engine-rest}
 * security chain: JWT validation, {@code KeycloakAuthenticationFilter}, {@link
 * VariableWritePolicyFilter} and the engine's JAX-RS resources.
 *
 * <p>Tokens are signed with a key generated here and served from a local JWKS endpoint that {@code
 * app.keycloak.jwk-set-uri} points at, so no Keycloak is needed. Users and groups live in the H2
 * identity tables (the Keycloak identity provider is mocked out).
 *
 * <p>{@code policyTest} (src/test/resources/authz) has an applicant form allowing {@code note} and
 * {@code amount}, and a review form allowing {@code decision} and {@code sendBackReason}; {@code
 * noPolicyTest} has no policy at all.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "camunda.bpm.job-execution.enabled=false",
      // Keycloak group ids contain hyphens; the H2 identity tables reject them by default.
      "camunda.bpm.generic-properties.properties.group-resource-whitelist-pattern=[a-zA-Z0-9_-]+",
      "app.variable-policy.locations=classpath*:processes/*/variable-policy.json,"
          + "classpath*:authz/*variable-policy.json"
    })
@ActiveProfiles("test")
class VariableWritePolicyFilterTest {

  private static final String ISSUER = "http://issuer.test/realms/cib7-poc";

  private static final RSAKey KEY;
  private static final HttpServer JWKS;

  static {
    try {
      KEY = new RSAKeyGenerator(2048).keyID("test-key").generate();
      JWKS = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      byte[] jwks = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
      JWKS.createContext(
          "/certs",
          exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, jwks.length);
            try (OutputStream out = exchange.getResponseBody()) {
              out.write(jwks);
            }
          });
      JWKS.start();
    } catch (JOSEException | IOException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  @DynamicPropertySource
  static void jwks(DynamicPropertyRegistry registry) {
    registry.add(
        "app.keycloak.jwk-set-uri",
        () -> "http://127.0.0.1:" + JWKS.getAddress().getPort() + "/certs");
    registry.add("app.keycloak.issuer-uri", () -> ISSUER);
  }

  @AfterAll
  static void stopJwks() {
    JWKS.stop(0);
  }

  @MockitoBean private KeycloakIdentityProvider keycloakIdentityProvider;

  @Autowired private ProcessEngine processEngine;

  @LocalServerPort private int port;

  private final HttpClient http = HttpClient.newHttpClient();

  @BeforeEach
  void setUp() {
    IdentityService identity = processEngine.getIdentityService();
    identity.clearAuthentication();
    member("bart", "applicant");
    member("homer", "civil-servant");
    member("admin", "cib7-admin");
    if (processEngine
            .getRepositoryService()
            .createProcessDefinitionQuery()
            .processDefinitionKey("policyTest")
            .count()
        == 0) {
      processEngine
          .getRepositoryService()
          .createDeployment()
          .name("variable-policy-test")
          .addClasspathResource("authz/variable-policy-test.bpmn")
          .deploy();
    }
  }

  @Test
  void tokenWithWrongAudienceOrIssuerIsRejected() throws Exception {
    assertEquals(200, get(token("bart", ISSUER, "cib7-rest-api")).statusCode());
    assertEquals(401, get(token("bart", ISSUER, "some-other-api")).statusCode());
    assertEquals(
        401, get(token("bart", "http://evil.test/realms/cib7-poc", "cib7-rest-api")).statusCode());
    assertEquals(401, get(token("bart", null, "cib7-rest-api")).statusCode());
  }

  private HttpResponse<String> get(String bearer) throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/engine-rest/task"))
            .header("Authorization", "Bearer " + bearer)
            .GET()
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  @Test
  void applicantCannotAddConfigBeanDecisionOrDmnOutputToOwnForm() throws Exception {
    String pi = start("bart", "policyTest", "{}");
    Task task = openTask(pi);
    for (String forbidden : List.of("busBaseUrl", "decision", "autoDecision", "initiator")) {
      HttpResponse<String> response =
          post(
              "bart",
              "/task/" + task.getId() + "/complete",
              "{\"variables\":{\"note\":{\"value\":\"hi\",\"type\":\"String\"},\""
                  + forbidden
                  + "\":{\"value\":\"x\",\"type\":\"String\"}}}");
      assertEquals(403, response.statusCode(), forbidden + ": " + response.body());
      assertTrue(response.body().contains("\"VariablePolicyViolation\""), response.body());
      assertTrue(
          response.body().contains("variable '" + forbidden + "' is not writable"),
          response.body());
      assertNotNull(
          processEngine.getTaskService().createTaskQuery().taskId(task.getId()).singleResult(),
          "task stays open after a refused completion with " + forbidden);
      // initiator was set by the engine at start; the refused write must not have replaced it.
      assertEquals(
          forbidden.equals("initiator") ? "bart" : null,
          processEngine.getRuntimeService().getVariable(pi, forbidden),
          forbidden + " unchanged");
    }
  }

  @Test
  void applicantCompletesOwnFormWithAllowedVariables() throws Exception {
    String pi = start("bart", "policyTest", "{}");
    Task task = openTask(pi);
    HttpResponse<String> response =
        post(
            "bart",
            "/task/" + task.getId() + "/complete",
            "{\"variables\":{\"note\":{\"value\":\"hi\",\"type\":\"String\"},"
                + "\"amount\":{\"value\":3,\"type\":\"Integer\"}}}");
    assertEquals(204, response.statusCode(), response.body());
    assertEquals("hi", processEngine.getRuntimeService().getVariable(pi, "note"));
    assertEquals("Task_Review", openTask(pi).getTaskDefinitionKey());
  }

  @Test
  void reviewerMayWriteDecisionButNotApplicantFields() throws Exception {
    String pi = start("bart", "policyTest", "{}");
    assertEquals(
        204, post("bart", "/task/" + openTask(pi).getId() + "/complete", "{}").statusCode());
    Task review = openTask(pi);

    HttpResponse<String> refused =
        post(
            "homer",
            "/task/" + review.getId() + "/complete",
            "{\"variables\":{\"note\":{\"value\":\"rewritten\",\"type\":\"String\"}}}");
    assertEquals(403, refused.statusCode(), refused.body());
    assertTrue(refused.body().contains("from form 'policy-review'"), refused.body());

    HttpResponse<String> accepted =
        post(
            "homer",
            "/task/" + review.getId() + "/complete",
            "{\"variables\":{\"decision\":{\"value\":\"approve\",\"type\":\"String\"}}}");
    assertEquals(204, accepted.statusCode(), accepted.body());
  }

  @Test
  void startWithUnlistedVariableIsRefused() throws Exception {
    HttpResponse<String> refused =
        post(
            "bart",
            "/process-definition/key/policyTest/start",
            "{\"variables\":{\"autoDecision\":{\"value\":\"approve\",\"type\":\"String\"}}}");
    assertEquals(403, refused.statusCode(), refused.body());
    assertTrue(refused.body().contains("when starting 'policyTest'"), refused.body());

    HttpResponse<String> accepted =
        post(
            "bart",
            "/process-definition/key/policyTest/start",
            "{\"variables\":{\"note\":{\"value\":\"hi\",\"type\":\"String\"}}}");
    assertEquals(200, accepted.statusCode(), accepted.body());
  }

  @Test
  void startInstructionsAndSkipFlagsAreAdminOnly() throws Exception {
    assertEquals(
        403,
        post(
                "bart",
                "/process-definition/key/policyTest/start",
                "{\"startInstructions\":[{\"type\":\"startBeforeActivity\","
                    + "\"activityId\":\"Task_Review\"}]}")
            .statusCode());
    assertEquals(
        403,
        post("bart", "/process-definition/key/policyTest/start", "{\"skipCustomListeners\":true}")
            .statusCode());
  }

  @Test
  void applicantCannotWriteVariablesOutsideAForm() throws Exception {
    String pi = start("bart", "policyTest", "{}");
    String var = "{\"value\":\"approve\",\"type\":\"String\"}";
    assertEquals(403, send("bart", "PUT", "/process-instance/" + pi + "/variables/decision", var));
    assertEquals(
        403,
        send(
            "bart",
            "POST",
            "/process-instance/" + pi + "/variables",
            "{\"modifications\":{\"decision\":" + var + "}}"));
    assertEquals(403, send("bart", "DELETE", "/process-instance/" + pi + "/variables/note", ""));
    assertEquals(
        403,
        send(
            "bart",
            "POST",
            "/message",
            "{\"messageName\":\"x\",\"processInstanceId\":\"" + pi + "\"}"));
    String taskId = openTask(pi).getId();
    assertEquals(403, send("bart", "PUT", "/task/" + taskId + "/variables/decision", var));
    assertNull(processEngine.getRuntimeService().getVariable(pi, "decision"));
  }

  @Test
  void pathTricksDoNotBypassThePolicy() throws Exception {
    String pi = start("bart", "policyTest", "{}");
    String taskId = openTask(pi).getId();
    String body = "{\"variables\":{\"decision\":{\"value\":\"approve\",\"type\":\"String\"}}}";
    assertEquals(403, send("bart", "POST", "/engine/default/task/" + taskId + "/complete", body));
    assertEquals(403, send("bart", "POST", "/task/" + taskId + "/compl%65te", body));
    assertEquals(403, send("bart", "POST", "/task/" + taskId + "/complete/", body));
    assertEquals(
        403,
        send(
            "bart",
            "POST",
            "/task/" + taskId + "/complete",
            "{\"Variables\":{\"decision\":{\"value\":\"approve\",\"type\":\"String\"}}}"));
    assertNotNull(processEngine.getTaskService().createTaskQuery().taskId(taskId).singleResult());
  }

  @Test
  void draftsUseTheFormAllowlist() throws Exception {
    String pi = start("bart", "policyTest", "{}");
    String taskId = openTask(pi).getId();
    assertEquals(
        204,
        send(
            "bart",
            "POST",
            "/task/" + taskId + "/localVariables",
            "{\"modifications\":{\"note\":{\"value\":\"draft\",\"type\":\"String\"}}}"));
    assertEquals(
        403,
        send(
            "bart",
            "POST",
            "/task/" + taskId + "/localVariables",
            "{\"modifications\":{\"busBaseUrl\":{\"value\":\"http://evil\",\"type\":\"String\"}}}"));
  }

  @Test
  void adminBypassesThePolicy() throws Exception {
    String pi = start("bart", "policyTest", "{}");
    assertEquals(
        204,
        send(
            "admin",
            "PUT",
            "/process-instance/" + pi + "/variables/sendBackReason",
            "{\"value\":\"set by the backend\",\"type\":\"String\"}"));
    assertEquals(
        "set by the backend", processEngine.getRuntimeService().getVariable(pi, "sendBackReason"));
  }

  @Test
  void definitionWithoutPolicyFailsClosed() throws Exception {
    String pi = start("bart", "noPolicyTest", "{}");
    assertEquals(
        403,
        post(
                "bart",
                "/process-definition/key/noPolicyTest/start",
                "{\"variables\":{\"note\":{\"value\":\"hi\",\"type\":\"String\"}}}")
            .statusCode());
    String taskId = openTask(pi).getId();
    HttpResponse<String> refused =
        post(
            "bart",
            "/task/" + taskId + "/complete",
            "{\"variables\":{\"note\":{\"value\":\"hi\",\"type\":\"String\"}}}");
    assertEquals(403, refused.statusCode(), refused.body());
    assertEquals(204, post("bart", "/task/" + taskId + "/complete", "{}").statusCode());
  }

  private String start(String user, String key, String body) throws Exception {
    HttpResponse<String> response = post(user, "/process-definition/key/" + key + "/start", body);
    assertEquals(200, response.statusCode(), response.body());
    String marker = "\"id\":\"";
    int at = response.body().indexOf(marker) + marker.length();
    return response.body().substring(at, response.body().indexOf('"', at));
  }

  private Task openTask(String processInstanceId) {
    return processEngine
        .getTaskService()
        .createTaskQuery()
        .processInstanceId(processInstanceId)
        .singleResult();
  }

  private HttpResponse<String> post(String user, String path, String body) throws Exception {
    return request(user, "POST", path, body);
  }

  private int send(String user, String method, String path, String body) throws Exception {
    return request(user, method, path, body).statusCode();
  }

  private HttpResponse<String> request(String user, String method, String path, String body)
      throws Exception {
    HttpRequest request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/engine-rest" + path))
            .header("Authorization", "Bearer " + token(user))
            .header("Content-Type", "application/json")
            .method(method, HttpRequest.BodyPublishers.ofString(body))
            .build();
    return http.send(request, HttpResponse.BodyHandlers.ofString());
  }

  private static String token(String user) throws JOSEException {
    return token(user, ISSUER, "cib7-rest-api");
  }

  private static String token(String user, String issuer, String audience) throws JOSEException {
    Instant now = Instant.now();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(),
            new JWTClaimsSet.Builder()
                .subject(user)
                .issuer(issuer)
                .claim("preferred_username", user)
                .audience(audience)
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(300)))
                .build());
    jwt.sign(new RSASSASigner(KEY));
    return jwt.serialize();
  }

  private void member(String userId, String groupId) {
    IdentityService identity = processEngine.getIdentityService();
    if (identity.createUserQuery().userId(userId).count() == 0) {
      User user = identity.newUser(userId);
      user.setFirstName(userId);
      user.setLastName("Test");
      user.setEmail(userId + "@example.test");
      identity.saveUser(user);
    }
    if (identity.createGroupQuery().groupId(groupId).count() == 0) {
      Group group = identity.newGroup(groupId);
      group.setName(groupId);
      identity.saveGroup(group);
    }
    if (identity.createGroupQuery().groupId(groupId).groupMember(userId).count() == 0) {
      identity.createMembership(userId, groupId);
    }
  }
}
