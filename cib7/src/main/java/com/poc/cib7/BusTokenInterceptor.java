package com.poc.cib7;

import java.util.ArrayList;
import java.util.List;
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
 * Stamps {@code X-Bus-Token} on every http-connector request addressed to the integration bus, so
 * the bus can refuse callers other than the engine.
 *
 * <p>Applied centrally rather than as a BPMN header so no generated service can forget it and no
 * template ever holds the secret. Requests to any other host get no token, so a URL that somehow
 * points elsewhere cannot carry it away. {@code setHeader} replaces any header of the same name the
 * BPMN might have set.
 *
 * <p>The Spring Boot starter registers the Connect and Spin plugins itself; this class is also an
 * engine plugin only to attach itself to the http-connector, which the Connect plugin loads in its
 * {@code preInit}, before any plugin's {@code postInit} runs.
 */
@Component
public class BusTokenInterceptor extends AbstractProcessEnginePlugin
    implements ConnectorRequestInterceptor {

  static final String HEADER = "X-Bus-Token";

  private final String busBaseUrl;
  private final String token;

  public BusTokenInterceptor(
      @Qualifier("busBaseUrl") String busBaseUrl, @Value("${app.bus.token}") String token) {
    this.busBaseUrl = busBaseUrl.endsWith("/") ? busBaseUrl : busBaseUrl + "/";
    this.token = token;
  }

  @Override
  public void postInit(ProcessEngineConfigurationImpl configuration) {
    Connector<?> http = Connectors.getConnector(Connectors.HTTP_CONNECTOR_ID);
    // Connectors is a JVM-wide singleton; a second engine in the same JVM
    // (tests) must not stack a second interceptor.
    List<ConnectorRequestInterceptor> interceptors = new ArrayList<>(http.getRequestInterceptors());
    interceptors.removeIf(BusTokenInterceptor.class::isInstance);
    interceptors.add(this);
    http.setRequestInterceptors(interceptors);
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
