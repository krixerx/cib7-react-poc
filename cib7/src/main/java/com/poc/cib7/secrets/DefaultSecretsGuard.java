package com.poc.cib7.secrets;

import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * Startup guard against running the engine on the committed dev-default outbound secrets, the
 * engine's copy of the backend's guard of the same name. The defaults in {@code application.yaml}
 * make {@code docker compose up} work with zero setup, but they are public, so a deployment that
 * still runs on them hands Patchbay's integration API and the backend's {@code /api/internal/**} to
 * anyone on their networks.
 *
 * <p>Logs a WARN whenever a known dev default is in effect, and refuses to start when the {@code
 * prod} Spring profile is active or {@code APP_REQUIRE_REAL_SECRETS=true} is set.
 */
@Component
public class DefaultSecretsGuard implements InitializingBean {

  private static final Logger log = LoggerFactory.getLogger(DefaultSecretsGuard.class);

  /** Effective-config property → the committed dev default it must not equal in prod. */
  static final Map<String, String> DEV_DEFAULTS =
      Map.of(
          "app.patchbay.api-key", "patchbay-api-key-change-me",
          "app.backend.internal-token", "internal-task-token-change-me");

  private final Environment env;

  public DefaultSecretsGuard(Environment env) {
    this.env = env;
  }

  @Override
  public void afterPropertiesSet() {
    List<String> hits =
        DEV_DEFAULTS.entrySet().stream()
            .filter(e -> isDefaultOrBlank(env.getProperty(e.getKey()), e.getValue()))
            .map(Map.Entry::getKey)
            .sorted()
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
        "Dev-default secrets in use for {}: fine for a local demo, never for a real deployment "
            + "(the 'prod' profile or APP_REQUIRE_REAL_SECRETS=true turns this into a startup "
            + "failure).",
        hits);
  }

  /**
   * A blank value counts as a default: Patchbay 0.1.0 lets every integration call through when its
   * key is empty, so an engine with no key means an open integration API, not a real secret.
   */
  private static boolean isDefaultOrBlank(String value, String devDefault) {
    return value == null || value.isBlank() || devDefault.equals(value);
  }
}
