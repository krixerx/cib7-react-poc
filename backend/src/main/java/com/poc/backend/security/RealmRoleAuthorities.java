package com.poc.backend.security;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

/**
 * Adds the Keycloak realm roles ({@code realm_access.roles}) to a JWT's authorities as {@code
 * ROLE_<name>}, next to Spring's default {@code SCOPE_*} ones, so a filter chain can say {@code
 * hasRole("statistics-viewer")}. The roles are read from the token, so granting or revoking a role
 * in Keycloak takes effect with the user's next token and needs no change here.
 */
public class RealmRoleAuthorities implements Converter<Jwt, Collection<GrantedAuthority>> {

  private final JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();

  @Override
  public Collection<GrantedAuthority> convert(Jwt jwt) {
    List<GrantedAuthority> authorities = new ArrayList<>(scopes.convert(jwt));
    Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
    if (realmAccess != null && realmAccess.get("roles") instanceof List<?> roles) {
      for (Object role : roles) {
        authorities.add(new SimpleGrantedAuthority("ROLE_" + role));
      }
    }
    return authorities;
  }
}
