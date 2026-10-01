package com.poc.cib7;

import org.apache.hc.core5.http.HttpRequest;
import org.cibseven.connect.spi.ConnectorInvocation;
import org.cibseven.connect.spi.ConnectorRequestInterceptor;

/**
 * Stamps {@code X-Bus-Token} on every http-connector request addressed to the integration bus, so
 * the bus can refuse callers other than the engine.
 *
 * <p>Applied centrally rather than as a BPMN header so no generated service can forget it and no
 * template ever holds the secret. Requests to any other host get no token, so a URL that somehow
 * points elsewhere cannot carry it away. {@code setHeader} replaces any header of the same name the
 * BPMN might have set.
 */
public class BusTokenInterceptor implements ConnectorRequestInterceptor {

  static final String HEADER = "X-Bus-Token";

  private final String busBaseUrl;
  private final String token;

  public BusTokenInterceptor(String busBaseUrl, String token) {
    this.busBaseUrl = busBaseUrl.endsWith("/") ? busBaseUrl : busBaseUrl + "/";
    this.token = token;
  }

  @Override
  public Object handleInvocation(ConnectorInvocation invocation) throws Exception {
    if (invocation.getTarget() instanceof HttpRequest request && addressedToBus(request)) {
      request.setHeader(HEADER, token);
    }
    return invocation.proceed();
  }

  private boolean addressedToBus(HttpRequest request) throws Exception {
    String uri = request.getUri().toString();
    return uri.startsWith(busBaseUrl);
  }
}
