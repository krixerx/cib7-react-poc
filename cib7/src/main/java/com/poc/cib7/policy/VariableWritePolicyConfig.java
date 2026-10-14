package com.poc.cib7.policy;

import org.cibseven.bpm.engine.IdentityService;
import org.cibseven.bpm.engine.RepositoryService;
import org.cibseven.bpm.engine.TaskService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.jersey.autoconfigure.JerseyApplicationPath;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Puts {@link VariableWritePolicyFilter} on {@code /engine-rest/*} right after the CIB seven
 * security starter's engine authentication filter.
 *
 * <p>The starter validates the Bearer JWT in the Spring Security chain and then, in a servlet
 * filter at {@code DEFAULT_FILTER_ORDER + 1}, sets the caller and their groups on the engine's
 * {@link IdentityService}. The policy needs that user, so it runs one step later, and the starter's
 * filter clears the user only after the whole chain has returned.
 */
@Configuration
public class VariableWritePolicyConfig {

  @Bean
  FilterRegistrationBean<VariableWritePolicyFilter> variableWritePolicyFilter(
      JerseyApplicationPath applicationPath,
      VariablePolicyRegistry variablePolicyRegistry,
      FormSchemaRegistry formSchemaRegistry,
      IdentityService identityService,
      TaskService taskService,
      RepositoryService repositoryService,
      @Value("${plugin.identity.keycloak.administratorGroupName:cib7-admin}") String adminGroup) {
    FilterRegistrationBean<VariableWritePolicyFilter> registration =
        new FilterRegistrationBean<>(
            new VariableWritePolicyFilter(
                variablePolicyRegistry,
                formSchemaRegistry,
                identityService,
                taskService,
                repositoryService,
                adminGroup));
    registration.setOrder(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 2);
    registration.addUrlPatterns(applicationPath.getPath() + "/*");
    return registration;
  }
}
