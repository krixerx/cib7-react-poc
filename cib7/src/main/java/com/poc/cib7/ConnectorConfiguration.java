package com.poc.cib7;

import org.cibseven.connect.plugin.impl.ConnectProcessEnginePlugin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the CIB seven Connect process-engine plugin.
 *
 * <p>Without this plugin the engine ignores {@code <camunda:connector>} blocks.
 * The CIB seven Spring Boot starter picks up every {@code ProcessEnginePlugin}
 * bean and wires it into the engine — so this single bean is all that is needed
 * for the "Get price" service task's rest-datasonnet connector to run.
 */
@Configuration
public class ConnectorConfiguration {

    @Bean
    public ConnectProcessEnginePlugin connectProcessEnginePlugin() {
        return new ConnectProcessEnginePlugin();
    }
}
