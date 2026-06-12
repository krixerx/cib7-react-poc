package com.poc.backend.security;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Startup guard against shipping the committed dev-default secrets. The defaults in {@code
 * application.yaml} make {@code docker compose up} work with zero setup — but they are public (this
 * repo is shared), so a deployment that still runs on them is effectively unauthenticated.
 *
 * <p>Behavior: log a WARN whenever a known dev default is in effect; refuse to start when the
 * {@code prod} Spring profile is active or {@code APP_REQUIRE_REAL_SECRETS=true} is set (the prod
 * compose file sets the latter).
 */
@Component
public class DefaultSecretsGuard implements InitializingBean {

  private static final Logger log = LoggerFactory.getLogger(DefaultSecretsGuard.class);

  /** Effective-config property → the committed dev default it must not equal in prod. */
  private static final Map<String, String> DEV_DEFAULTS =
      Map.of(
          "app.internal-task-token", "internal-task-token-change-me",
          "app.s3.secret-key", "cib7admin-secret-change-me",
          "spring.security.oauth2.client.registration.engine.client-secret",
              "cib7-business-secret");

  private final Environment env;

  public DefaultSecretsGuard(Environment env) {
    this.env = env;
  }

  @Override
  public void afterPropertiesSet() {
    List<String> hits =
        DEV_DEFAULTS.entrySet().stream()
            .filter(e -> e.getValue().equals(env.getProperty(e.getKey())))
            .map(Map.Entry::getKey)
            .toList();
    if (hits.isEmpty()) {
      return;
    }
    boolean enforce =
        env.acceptsProfiles(Profiles.of("prod"))
            || Boolean.parseBoolean(env.getProperty("app.require-real-secrets", "false"));
    if (enforce) {
      throw new IllegalStateException(
          "Refusing to start: dev-default secrets in effect for "
              + hits
              + ". Set real values via the corresponding environment variables.");
    }
    log.warn(
        "Dev-default secrets in use for {} — fine for a local demo, never for a real deployment "
            + "(the 'prod' profile or APP_REQUIRE_REAL_SECRETS=true turns this into a startup "
            + "failure).",
        hits);
  }
}
