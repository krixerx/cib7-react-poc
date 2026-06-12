package com.poc.cib7.keycloak;

import java.util.List;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Rejects JWTs whose {@code aud} claim does not contain the configured audience.
 *
 * <p>From the cibseven-keycloak plugin's reference example, repackaged under {@code
 * com.poc.cib7.keycloak}. Deviation from upstream: {@link Jwt#getAudience()} is {@code null} when
 * the token carries no {@code aud} claim at all (e.g. a bare client-credentials token), which the
 * upstream example NPEs on — that must fail as {@code invalid_token}, not a 500.
 */
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

  private final String audience;

  public AudienceValidator(String audience) {
    this.audience = audience;
  }

  @Override
  public OAuth2TokenValidatorResult validate(Jwt jwt) {
    List<String> audiences = jwt.getAudience();
    if (audiences != null && audiences.contains(audience)) {
      return OAuth2TokenValidatorResult.success();
    }
    return OAuth2TokenValidatorResult.failure(
        new OAuth2Error("invalid_token", "The required audience is missing", null));
  }
}
