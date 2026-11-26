package com.poc.cib7;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.hc.core5.http.HttpRequest;
import org.cibseven.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.connect.Connectors;
import org.cibseven.connect.spi.Connector;
import org.cibseven.connect.spi.ConnectorInvocation;
import org.cibseven.connect.spi.ConnectorRequestInterceptor;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Attaches each outbound credential only to the destination it belongs to (docs/cib7.md,
 * docs/security.md rule 9): {@code X-API-Key} to Patchbay's integration API, {@code
 * X-Internal-Token} to the backend's {@code /api/internal/}, and neither anywhere else.
 *
 * <p>Applied centrally rather than as a BPMN header so no template ever holds a secret and no pack
 * can point one at a host of its choosing. Both headers are removed from every request first, so a
 * header a BPMN connector set is replaced on the right destination and dropped on every other one.
 * Destinations are compared as parsed origins (scheme, host, effective port) and a path with its
 * dot segments removed, never as string prefixes: {@code http://backend:8085.evil/} has no valid
 * port and so no host, and {@code http://backend:8085@evil/} has the host {@code evil}.
 *
 * <p>The Spring Boot starter registers the Connect plugin itself; this class is also an engine
 * plugin only to attach itself to the http-connector, which the Connect plugin loads in its {@code
 * preInit}, before any plugin's {@code postInit} runs.
 */
@Component
public class OutboundCredentialsInterceptor extends AbstractProcessEnginePlugin
    implements ConnectorRequestInterceptor {

  static final String API_KEY_HEADER = "X-API-Key";
  static final String INTERNAL_TOKEN_HEADER = "X-Internal-Token";

  private final Destination patchbay;
  private final Destination backend;
  private final String apiKey;
  private final String internalToken;

  public OutboundCredentialsInterceptor(
      @Qualifier("patchbayBaseUrl") String patchbayBaseUrl,
      @Qualifier("backendBaseUrl") String backendBaseUrl,
      @Value("${app.patchbay.api-key}") String apiKey,
      @Value("${app.backend.internal-token}") String internalToken) {
    this.patchbay = Destination.of(patchbayBaseUrl, "/api/v1/integrations/");
    this.backend = Destination.of(backendBaseUrl, "/api/internal/");
    this.apiKey = apiKey;
    this.internalToken = internalToken;
  }

  @Override
  public void postInit(ProcessEngineConfigurationImpl configuration) {
    Connector<?> http = Connectors.getConnector(Connectors.HTTP_CONNECTOR_ID);
    // Connectors is a JVM-wide singleton; a second engine in the same JVM
    // (tests) must not stack a second interceptor.
    List<ConnectorRequestInterceptor> interceptors = new ArrayList<>(http.getRequestInterceptors());
    interceptors.removeIf(OutboundCredentialsInterceptor.class::isInstance);
    interceptors.add(this);
    http.setRequestInterceptors(interceptors);
  }

  @Override
  public Object handleInvocation(ConnectorInvocation invocation) throws Exception {
    if (invocation.getTarget() instanceof HttpRequest request) {
      request.removeHeaders(API_KEY_HEADER);
      request.removeHeaders(INTERNAL_TOKEN_HEADER);
      URI target = target(request);
      if (patchbay.matches(target)) {
        request.setHeader(API_KEY_HEADER, apiKey);
      } else if (backend.matches(target)) {
        request.setHeader(INTERNAL_TOKEN_HEADER, internalToken);
      }
    }
    return invocation.proceed();
  }

  private static URI target(HttpRequest request) {
    try {
      return request.getUri().normalize();
    } catch (URISyntaxException | IllegalArgumentException e) {
      return null;
    }
  }

  /** A configured base URL's origin plus the path prefix under it that carries the credential. */
  private record Destination(String scheme, String host, int port, String pathPrefix) {

    static Destination of(String baseUrl, String prefix) {
      URI base = URI.create(baseUrl).normalize();
      if (base.getHost() == null || base.getScheme() == null || base.getUserInfo() != null) {
        throw new IllegalStateException("Not a plain http(s) base URL: " + baseUrl);
      }
      String basePath = base.getRawPath() == null ? "" : base.getRawPath().replaceAll("/+$", "");
      String scheme = base.getScheme().toLowerCase(Locale.ROOT);
      return new Destination(
          scheme,
          base.getHost().toLowerCase(Locale.ROOT),
          effectivePort(scheme, base.getPort()),
          basePath + prefix);
    }

    boolean matches(URI uri) {
      if (uri == null
          || uri.getScheme() == null
          || uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getRawPath() == null) {
        return false;
      }
      String uriScheme = uri.getScheme().toLowerCase(Locale.ROOT);
      return uriScheme.equals(scheme)
          && uri.getHost().toLowerCase(Locale.ROOT).equals(host)
          && effectivePort(uriScheme, uri.getPort()) == port
          && uri.getRawPath().startsWith(pathPrefix);
    }

    private static int effectivePort(String scheme, int port) {
      if (port != -1) {
        return port;
      }
      return "https".equals(scheme) ? 443 : 80;
    }
  }
}
