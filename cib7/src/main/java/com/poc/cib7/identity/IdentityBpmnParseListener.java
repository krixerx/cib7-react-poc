package com.poc.cib7.identity;

import org.cibseven.bpm.engine.delegate.ExecutionListener;
import org.cibseven.bpm.engine.delegate.Expression;
import org.cibseven.bpm.engine.delegate.TaskListener;
import org.cibseven.bpm.engine.impl.bpmn.behavior.UserTaskActivityBehavior;
import org.cibseven.bpm.engine.impl.bpmn.parser.AbstractBpmnParseListener;
import org.cibseven.bpm.engine.impl.persistence.entity.ProcessDefinitionEntity;
import org.cibseven.bpm.engine.impl.pvm.process.ActivityImpl;
import org.cibseven.bpm.engine.impl.pvm.process.ScopeImpl;
import org.cibseven.bpm.engine.impl.task.TaskDefinition;
import org.cibseven.bpm.engine.impl.util.xml.Element;

/**
 * Wires the identity prefill and tamper-validation listeners onto every parsed process, without
 * touching the BPMN XML (which {@code /service-builder} regenerates from markdown specs and would
 * otherwise clobber).
 *
 * <ul>
 *   <li>Every process start event gets {@link IdentityPopulationListener}; it no-ops for processes
 *       not registered in {@link IdentityFieldRegistry}.
 *   <li>Only user tasks assigned to {@code ${initiator}} (the applicant / Part A tasks) get {@link
 *       IdentityValidationListener}. Civil-servant tasks ({@code candidateGroups=civil-servant})
 *       are left untouched — we trust them.
 * </ul>
 *
 * <p>The listeners are stateless, so a single shared instance of each is reused across all parsed
 * definitions.
 */
public class IdentityBpmnParseListener extends AbstractBpmnParseListener {

  private static final String INITIATOR_ASSIGNEE = "${initiator}";

  private final ExecutionListener populationListener = new IdentityPopulationListener();
  private final TaskListener validationListener = new IdentityValidationListener();

  @Override
  public void parseProcess(Element processElement, ProcessDefinitionEntity processDefinition) {
    processDefinition.addExecutionListener(ExecutionListener.EVENTNAME_START, populationListener);
  }

  @Override
  public void parseUserTask(Element userTaskElement, ScopeImpl scope, ActivityImpl activity) {
    if (!(activity.getActivityBehavior() instanceof UserTaskActivityBehavior behavior)) {
      return;
    }
    TaskDefinition taskDefinition = behavior.getTaskDefinition();
    Expression assignee = taskDefinition.getAssigneeExpression();
    if (assignee != null && INITIATOR_ASSIGNEE.equals(assignee.getExpressionText())) {
      taskDefinition.addTaskListener(TaskListener.EVENTNAME_COMPLETE, validationListener);
    }
  }
}
