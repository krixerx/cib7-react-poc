package com.poc.cib7.keycloak;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

class AudienceValidatorTest {

  private static final String REQUIRED_AUDIENCE = "cib7-rest-api";

  private final AudienceValidator validator = new AudienceValidator(REQUIRED_AUDIENCE);

  private static Jwt jwtWithAudience(String... audiences) {
    Jwt.Builder builder = Jwt.withTokenValue("test-token").header("alg", "RS256").subject("lisa");
    if (audiences.length > 0) {
      builder.audience(List.of(audiences));
    }
    return builder.build();
  }

  @Test
  void acceptsTokenCarryingTheRequiredAudience() {
    assertFalse(validator.validate(jwtWithAudience(REQUIRED_AUDIENCE)).hasErrors());
  }

  @Test
  void acceptsWhenRequiredAudienceIsOneOfSeveral() {
    assertFalse(
        validator.validate(jwtWithAudience("account", REQUIRED_AUDIENCE, "other")).hasErrors());
  }

  @Test
  void rejectsTokenWithOnlyForeignAudiences() {
    OAuth2TokenValidatorResult result = validator.validate(jwtWithAudience("account"));
    assertTrue(result.hasErrors());
    assertEquals("invalid_token", result.getErrors().iterator().next().getErrorCode());
  }

  @Test
  void doesNotMatchAudienceBySubstring() {
    assertTrue(validator.validate(jwtWithAudience(REQUIRED_AUDIENCE + "-extended")).hasErrors());
    assertTrue(validator.validate(jwtWithAudience("cib7")).hasErrors());
  }

  /**
   * Regression: {@link Jwt#getAudience()} returns {@code null} (not an empty list) when the token
   * has no {@code aud} claim — e.g. a bare client-credentials token from another realm client. That
   * must surface as a clean invalid_token failure, not an NPE that turns into a 500.
   */
  @Test
  void rejectsTokenWithoutAnyAudClaimInsteadOfThrowing() {
    OAuth2TokenValidatorResult result =
        assertDoesNotThrow(() -> validator.validate(jwtWithAudience()));
    assertTrue(result.hasErrors());
    assertEquals("invalid_token", result.getErrors().iterator().next().getErrorCode());
  }
}
