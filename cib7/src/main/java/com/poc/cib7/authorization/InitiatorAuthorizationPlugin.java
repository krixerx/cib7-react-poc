package com.poc.cib7.authorization;

import java.util.ArrayList;
import java.util.List;
import org.cibseven.bpm.engine.delegate.ExecutionListener;
import org.cibseven.bpm.engine.delegate.TaskListener;
import org.cibseven.bpm.engine.impl.bpmn.behavior.UserTaskActivityBehavior;
import org.cibseven.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.cibseven.bpm.engine.impl.bpmn.parser.BpmnParseListener;
import org.cibseven.bpm.engine.impl.cfg.AbstractProcessEnginePlugin;
import org.cibseven.bpm.engine.impl.cfg.ProcessEngineConfigurationImpl;
import org.cibseven.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cibseven.bpm.engine.impl.pvm.process.ScopeImpl;
import org.cibseven.bpm.engine.impl.util.xml.Element;
import org.springframework.stereotype.Component;

/**
 * Attaches {@link InitiatorAuthorizationListener} to every deployed process (on start) and every
 * user task (on create), without touching the generated BPMN.
 *
 * <p>A parse listener rather than BPMN markup because the BPMN is regenerated from the service
 * spec; a per-file listener would be one forgotten line away from an applicant who cannot see their
 * own case.
 */
@Component
public class InitiatorAuthorizationPlugin extends AbstractProcessEnginePlugin {

  @Override
  public void preInit(ProcessEngineConfigurationImpl configuration) {
    List<BpmnParseListener> listeners = configuration.getCustomPreBPMNParseListeners();
    if (listeners == null) {
      listeners = new ArrayList<>();
      configuration.setCustomPreBPMNParseListeners(listeners);
    }
    listeners.add(new ParseListener(new InitiatorAuthorizationListener()));
  }

  private static final class ParseListener extends AbstractBpmnParseListener {

    private final InitiatorAuthorizationListener listener;

    ParseListener(InitiatorAuthorizationListener listener) {
      this.listener = listener;
    }

    @Override
    public void parseProcess(Element processElement, ProcessDefinitionEntity processDefinition) {
      processDefinition.addExecutionListener(ExecutionListener.EVENTNAME_START, listener);
    }

    @Override
    public void parseUserTask(Element userTaskElement, ScopeImpl scope, ActivityImpl activity) {
      if (activity.getActivityBehavior() instanceof UserTaskActivityBehavior behavior) {
        behavior.getTaskDefinition().addTaskListener(TaskListener.EVENTNAME_CREATE, listener);
      }
    }
  }
}
