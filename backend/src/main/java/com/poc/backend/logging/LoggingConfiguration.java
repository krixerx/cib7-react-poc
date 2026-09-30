package com.poc.backend.logging;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

/**
 * Registers {@link MdcUserFilter} on every request so log events carry {@code user_id}.
 *
 * <p>The order is the only subtle thing here. Spring Security's {@code FilterChainProxy} sits at
 * servlet-filter order {@code -100}; it runs its internal filters, populates the {@code
 * SecurityContext}, and only then continues down the outer servlet chain. Registering one step
 * later therefore guarantees an authenticated principal is available, while registering earlier
 * would silently see an empty context and never log a user at all.
 *
 * <p>The {@code -100} is Spring Boot's {@code SecurityProperties.DEFAULT_FILTER_ORDER}, inlined
 * because Boot 4 dropped that constant and the engine module needs the same number.
 */
@Configuration
public class LoggingConfiguration {

  private static final int AFTER_SPRING_SECURITY = -99;

  @Bean
  public FilterRegistrationBean<MdcUserFilter> mdcUserFilter(Environment environment) {
    String userNameAttribute =
        environment.getProperty("app.keycloak.user-name-attribute", "preferred_username");

    FilterRegistrationBean<MdcUserFilter> registration =
        new FilterRegistrationBean<>(new MdcUserFilter(userNameAttribute));
    registration.addUrlPatterns("/*");
    registration.setOrder(AFTER_SPRING_SECURITY);
    return registration;
  }
}
