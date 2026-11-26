package com.poc.cib7.secrets;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.env.MockEnvironment;

/**
 * The engine refuses to start on a committed outbound dev default once real secrets are required.
 */
class DefaultSecretsGuardTest {

  private static MockEnvironment realSecrets() {
    return new MockEnvironment()
        .withProperty("app.patchbay.api-key", "a-real-generated-api-key")
        .withProperty("app.backend.internal-token", "a-real-generated-internal-token");
  }

  @ParameterizedTest
  @CsvSource({
    "app.patchbay.api-key, patchbay-api-key-change-me",
    "app.backend.internal-token, internal-task-token-change-me"
  })
  void devDefaultIsRefusedWhenRealSecretsAreRequired(String property, String devDefault) {
    MockEnvironment env =
        realSecrets()
            .withProperty("app.require-real-secrets", "true")
            .withProperty(property, devDefault);
    IllegalStateException refused =
        assertThrows(IllegalStateException.class, new DefaultSecretsGuard(env)::afterPropertiesSet);
    assertTrue(refused.getMessage().startsWith("Refusing to start"), refused.getMessage());
    assertTrue(refused.getMessage().contains(property), refused.getMessage());
  }

  @Test
  void devDefaultIsRefusedUnderTheProdProfile() {
    MockEnvironment env =
        realSecrets().withProperty("app.patchbay.api-key", "patchbay-api-key-change-me");
    env.setActiveProfiles("prod");
    assertThrows(IllegalStateException.class, new DefaultSecretsGuard(env)::afterPropertiesSet);
  }

  @ParameterizedTest
  @ValueSource(strings = {"app.patchbay.api-key", "app.backend.internal-token"})
  void blankSecretIsRefusedWhenRealSecretsAreRequired(String property) {
    MockEnvironment env =
        realSecrets().withProperty("app.require-real-secrets", "true").withProperty(property, " ");
    assertThrows(IllegalStateException.class, new DefaultSecretsGuard(env)::afterPropertiesSet);
  }

  @Test
  void realSecretsPass() {
    MockEnvironment env = realSecrets().withProperty("app.require-real-secrets", "true");
    assertDoesNotThrow(new DefaultSecretsGuard(env)::afterPropertiesSet);
  }

  @Test
  void devDefaultsOnlyWarnInADemo() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("app.patchbay.api-key", "patchbay-api-key-change-me")
            .withProperty("app.backend.internal-token", "internal-task-token-change-me");
    assertDoesNotThrow(new DefaultSecretsGuard(env)::afterPropertiesSet);
  }

  @Test
  void theDefaultsAreTheOnesApplicationYamlShips() throws Exception {
    String yaml =
        new String(
            getClass().getResourceAsStream("/application.yaml").readAllBytes(),
            java.nio.charset.StandardCharsets.UTF_8);
    assertTrue(yaml.contains("${PATCHBAY_API_KEY:patchbay-api-key-change-me}"));
    assertTrue(yaml.contains("${INTERNAL_TASK_TOKEN:internal-task-token-change-me}"));
  }
}
