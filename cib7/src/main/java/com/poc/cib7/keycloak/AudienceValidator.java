package com.poc.cib7.keycloak;

import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects JWTs whose {@code aud} claim does not contain the configured audience.
 *
 * <p>Verbatim from the cibseven-keycloak plugin's reference example, repackaged under {@code
 * com.poc.cib7.keycloak}.
 */
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

  private final String audience;

  public AudienceValidator(String audience) {
    this.audience = audience;
  }

  @Override
  public OAuth2TokenValidatorResult validate(Jwt jwt) {
    if (jwt.getAudience().contains(audience)) {
      return OAuth2TokenValidatorResult.success();
    }
    return OAuth2TokenValidatorResult.failure(
        new OAuth2Error("invalid_token", "The required audience is missing", null));
  }
}
