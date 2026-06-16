package com.poc.cib7.identity;

import java.util.ArrayList;
import java.util.List;
import org.cibseven.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.cibseven.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.springframework.stereotype.Component;

/**
 * Registers {@link IdentityBpmnParseListener} as a custom pre-parse listener so every deployed
 * process gets applicant-identity prefill (at start) and tamper validation (on applicant task
 * completion).
 *
 * <p>The CIB seven Spring Boot starter discovers every {@code ProcessEnginePlugin} bean and wires
 * it into the engine — the same mechanism that activates {@code ConnectorConfiguration}'s plugins
 * and {@code KeycloakIdentityProvider}.
 */
@Component
public class IdentityProcessEnginePlugin extends AbstractProcessEnginePlugin {

  @Override
  public void preInit(ProcessEngineConfigurationImpl configuration) {
    List<BpmnParseListener> preParseListeners = configuration.getCustomPreBPMNParseListeners();
    if (preParseListeners == null) {
      preParseListeners = new ArrayList<>();
      configuration.setCustomPreBPMNParseListeners(preParseListeners);
    }
    preParseListeners.add(new IdentityBpmnParseListener());
  }
}
