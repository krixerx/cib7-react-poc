package com.poc.backend.security;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** The guard refuses to start on a committed dev-default secret once real secrets are required. */
class DefaultSecretsGuardTest {

  @Test
  void devDefaultDatabasePasswordIsRefusedWhenRealSecretsAreRequired() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("app.require-real-secrets", "true")
            .withProperty("spring.datasource.password", "backend-db-change-me");
    IllegalStateException refused =
        assertThrows(IllegalStateException.class, new DefaultSecretsGuard(env)::afterPropertiesSet);
    assertTrue(refused.getMessage().contains("spring.datasource.password"), refused.getMessage());
  }

  @Test
  void realDatabasePasswordPasses() {
    MockEnvironment env =
        new MockEnvironment()
            .withProperty("app.require-real-secrets", "true")
            .withProperty("spring.datasource.password", "a-real-generated-password");
    assertDoesNotThrow(new DefaultSecretsGuard(env)::afterPropertiesSet);
  }

  @Test
  void devDefaultsOnlyWarnInADemo() {
    MockEnvironment env =
        new MockEnvironment().withProperty("spring.datasource.password", "backend-db-change-me");
    assertDoesNotThrow(new DefaultSecretsGuard(env)::afterPropertiesSet);
  }
}
