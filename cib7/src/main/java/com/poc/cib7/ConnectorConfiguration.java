package com.poc.cib7;

import org.cibseven.connect.plugin.impl.ConnectProcessEnginePlugin;
import org.cibseven.spin.plugin.impl.SpinProcessEnginePlugin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the engine plugins that support the "Get price" service task.
 *
 * <p>The CIB seven Spring Boot starter picks up every {@code ProcessEnginePlugin}
 * bean and wires it into the engine.
 *
 * <ul>
 *   <li>{@link ConnectProcessEnginePlugin} — makes the engine parse and run
 *       {@code <camunda:connector>} blocks. Without it the connector on the
 *       "Get price" service task is ignored.
 *   <li>{@link SpinProcessEnginePlugin} — registers the {@code S()}, {@code JSON()},
 *       and {@code XML()} JUEL functions so BPMN expressions can read into the
 *       response body of the {@code http-connector} (e.g.
 *       {@code ${S(response).prop('data').prop('price').numberValue()}}).
 * </ul>
 */
@Configuration
public class ConnectorConfiguration {

    @Bean
    public ConnectProcessEnginePlugin connectProcessEnginePlugin() {
        return new ConnectProcessEnginePlugin();
    }

    @Bean
    public SpinProcessEnginePlugin spinProcessEnginePlugin() {
        return new SpinProcessEnginePlugin();
    }
}
