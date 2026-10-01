package com.poc.cib7;

import java.util.ArrayList;
import java.util.List;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.connect.Connectors;
import org.cibseven.connect.plugin.impl.ConnectProcessEnginePlugin;
import org.cibseven.connect.spi.Connector;
import org.cibseven.connect.spi.ConnectorRequestInterceptor;
import org.cibseven.spin.plugin.impl.SpinProcessEnginePlugin;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the engine plugins behind the BPMN connector service tasks.
 *
 * <p>The CIB seven Spring Boot starter picks up every {@code ProcessEnginePlugin} bean and wires it
 * into the engine.
 *
 * <ul>
 *   <li>{@link ConnectProcessEnginePlugin} makes the engine parse and run {@code
 *       <camunda:connector>} blocks. After it has loaded the connectors, the http-connector gets a
 *       {@link BusTokenInterceptor}, so every call to the integration bus authenticates with {@code
 *       X-Bus-Token} ({@code app.bus.token}, env {@code BUS_TOKEN}).
 *   <li>{@link SpinProcessEnginePlugin} registers the {@code S()}, {@code JSON()}, and {@code
 *       XML()} JUEL functions so BPMN expressions can read into the response body of the {@code
 *       http-connector} (e.g. {@code ${S(response).prop('data').prop('price').numberValue()}}).
 * </ul>
 */
@Configuration
public class ConnectorConfiguration {

  @Bean
  public ConnectProcessEnginePlugin connectProcessEnginePlugin(
      @Qualifier("busBaseUrl") String busBaseUrl, @Value("${app.bus.token}") String busToken) {
    return new ConnectProcessEnginePlugin() {
      @Override
      public void postInit(ProcessEngineConfigurationImpl configuration) {
        super.postInit(configuration);
        Connector<?> http = Connectors.getConnector(Connectors.HTTP_CONNECTOR_ID);
        // Connectors is a JVM-wide singleton; a second engine in the same JVM
        // (tests) must not stack a second interceptor.
        List<ConnectorRequestInterceptor> interceptors =
            new ArrayList<>(http.getRequestInterceptors());
        interceptors.removeIf(BusTokenInterceptor.class::isInstance);
        interceptors.add(new BusTokenInterceptor(busBaseUrl, busToken));
        http.setRequestInterceptors(interceptors);
      }
    };
  }

  @Bean
  public SpinProcessEnginePlugin spinProcessEnginePlugin() {
    return new SpinProcessEnginePlugin();
  }
}
