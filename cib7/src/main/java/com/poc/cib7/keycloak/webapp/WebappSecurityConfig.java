package com.poc.cib7.keycloak.webapp;

import static org.springframework.security.config.Customizer.withDefaults;
import static org.springframework.security.web.util.matcher.AntPathRequestMatcher.antMatcher;

import jakarta.inject.Inject;
import java.util.Collections;
import org.cibseven.bpm.spring.boot.starter.property.CamundaBpmProperties;
import org.cibseven.bpm.webapp.impl.security.auth.ContainerBasedAuthenticationFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestRedirectFilter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.firewall.HttpFirewall;
import org.springframework.security.web.firewall.StrictHttpFirewall;
import org.springframework.web.context.request.RequestContextListener;
import org.springframework.web.filter.ForwardedHeaderFilter;

/**
 * Spring Security wiring for the legacy CIB seven webapps (Cockpit / Tasklist / Admin), served from
 * {@code /camunda/**}.
 *
 * <p>Verbatim from the cibseven-keycloak plugin's {@code sso-kubernetes} example, repackaged under
 * {@code com.poc.cib7.keycloak.webapp}. The filter chain matches webapp paths only (plus OAuth2's
 * {@code /oauth2/authorization/**}, {@code /login/**}, {@code /logout}) and uses Authorization Code
 * login against Keycloak. The {@link ContainerBasedAuthenticationFilter}, registered as a servlet
 * filter at order 201, runs after Spring Security has populated the principal and hands the user +
 * groups to the engine's IdentityService via {@link KeycloakAuthenticationProvider}.
 *
 * <p>The {@code @Order(2)} on this chain places it after the {@code @Order(1)} chain for {@code
 * /engine-rest/**} in {@link com.poc.cib7.keycloak.RestApiSecurityConfig}, so REST requests keep
 * going through the Bearer-JWT path.
 */
@EnableWebSecurity
@Configuration
public class WebappSecurityConfig {

  private final String legacyWebappPath;

  @Inject private KeycloakLogoutHandler keycloakLogoutHandler;

  public WebappSecurityConfig(CamundaBpmProperties properties) {
    this.legacyWebappPath = properties.getWebapp().getLegacyApplicationPath();
  }

  @Bean
  @Order(2)
  public SecurityFilterChain webappSecurity(HttpSecurity http) throws Exception {
    return http.securityMatcher(
            request -> {
              String path =
                  request.getServletPath()
                      + (request.getPathInfo() != null ? request.getPathInfo() : "");
              return path.startsWith(legacyWebappPath)
                  || path.startsWith(
                      OAuth2AuthorizationRequestRedirectFilter
                          .DEFAULT_AUTHORIZATION_REQUEST_BASE_URI)
                  || path.startsWith("/login")
                  || path.startsWith("/logout");
            })
        .csrf(
            csrf ->
                csrf.ignoringRequestMatchers(
                    antMatcher(legacyWebappPath + "/api/**"), antMatcher("/engine-rest/**")))
        .authorizeHttpRequests(
            authorize ->
                authorize
                    .requestMatchers(
                        antMatcher(legacyWebappPath + "/assets/**"),
                        antMatcher(legacyWebappPath + "/app/**"),
                        antMatcher(legacyWebappPath + "/api/**"),
                        antMatcher(legacyWebappPath + "/lib/**"))
                    .authenticated()
                    .anyRequest()
                    .permitAll())
        .oauth2Login(withDefaults())
        .logout(
            logout ->
                logout
                    .logoutRequestMatcher(antMatcher(legacyWebappPath + "/app/**/logout"))
                    .logoutSuccessHandler(keycloakLogoutHandler))
        .build();
  }

  @Bean
  @SuppressWarnings({"rawtypes", "unchecked"})
  public FilterRegistrationBean containerBasedAuthenticationFilter() {
    FilterRegistrationBean filterRegistration = new FilterRegistrationBean();
    filterRegistration.setFilter(new ContainerBasedAuthenticationFilter());
    filterRegistration.setInitParameters(
        Collections.singletonMap(
            "authentication-provider",
            "com.poc.cib7.keycloak.webapp.KeycloakAuthenticationProvider"));
    filterRegistration.setOrder(201);
    filterRegistration.addUrlPatterns(legacyWebappPath + "/app/*");
    return filterRegistration;
  }

  @Bean
  public FilterRegistrationBean<ForwardedHeaderFilter> forwardedHeaderFilter() {
    FilterRegistrationBean<ForwardedHeaderFilter> bean = new FilterRegistrationBean<>();
    bean.setFilter(new ForwardedHeaderFilter());
    bean.setOrder(Ordered.HIGHEST_PRECEDENCE);
    return bean;
  }

  @Bean
  @Order(0)
  public RequestContextListener requestContextListener() {
    return new RequestContextListener();
  }

  @Bean
  public HttpFirewall httpFirewall() {
    StrictHttpFirewall firewall = new StrictHttpFirewall();
    firewall.setAllowUrlEncodedPercent(true);
    firewall.setAllowUrlEncodedSlash(true);
    return firewall;
  }
}
