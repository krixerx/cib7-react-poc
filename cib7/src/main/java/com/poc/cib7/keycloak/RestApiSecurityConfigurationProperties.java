package com.poc.cib7.keycloak;

import jakarta.validation.constraints.NotEmpty;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

/**
 * Binds the {@code rest.security.*} block from {@code application.yaml}.
 *
 * <p>Verbatim from the cibseven-keycloak plugin's reference example, repackaged
 * under {@code com.poc.cib7.keycloak}.
 */
@Component
@ConfigurationProperties(prefix = "rest.security")
@Validated
public class RestApiSecurityConfigurationProperties {

    /** rest.security.enabled — switch the filter off by setting to false. */
    private Boolean enabled = true;

    /** rest.security.provider — name of the {@code spring.security.oauth2.client.provider} to use. */
    @NotEmpty
    private String provider;

    /** rest.security.required-audience — JWT {@code aud} claim must contain this value. */
    @NotEmpty
    private String requiredAudience;

    public String getRequiredAudience() {
        return requiredAudience;
    }

    public void setRequiredAudience(String requiredAudience) {
        this.requiredAudience = requiredAudience;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }

    public String getProvider() {
        return provider;
    }

    public void setProvider(String provider) {
        this.provider = provider;
    }
}
