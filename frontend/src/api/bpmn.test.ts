// @vitest-environment happy-dom
//
// bpmn.ts uses the browser-global DOMParser, which does not exist in plain
// Node — happy-dom provides it for this file only.

import { describe, expect, it } from 'vitest';

import { nextSteps, parseFlowGraph, parseProcessName, parseUserTasks } from './bpmn';

/**
 * Minimal but well-formed BPMN: start event → user task → exclusive gateway
 * branching to an approved and a (default) rejected end event.
 */
const BPMN_XML = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
    xmlns:camunda="http://camunda.org/schema/1.0/bpmn"
    targetNamespace="http://cib7-poc.test/bpmn">
  <bpmn:process id="toyRegistration" name="Toy Registration" isExecutable="true">
    <bpmn:startEvent id="StartEvent_1" name="Application received" />
    <bpmn:userTask id="Task_Review" name="Review application" camunda:formKey="react:toy-review" />
    <bpmn:exclusiveGateway id="Gateway_Decision" name="Approved?" default="Flow_ToRejected" />
    <bpmn:endEvent id="EndEvent_Approved" name="Approved" />
    <bpmn:endEvent id="EndEvent_Rejected" name="Rejected" />
    <bpmn:sequenceFlow id="Flow_Start" sourceRef="StartEvent_1" targetRef="Task_Review" />
    <bpmn:sequenceFlow id="Flow_ToGateway" sourceRef="Task_Review" targetRef="Gateway_Decision" />
    <bpmn:sequenceFlow id="Flow_ToApproved" sourceRef="Gateway_Decision" targetRef="EndEvent_Approved" />
    <bpmn:sequenceFlow id="Flow_ToRejected" sourceRef="Gateway_Decision" targetRef="EndEvent_Rejected" />
  </bpmn:process>
</bpmn:definitions>`;

describe('parseUserTasks', () => {
  it('finds the user task with id, name and camunda:formKey', () => {
    expect(parseUserTasks(BPMN_XML)).toEqual([
      { id: 'Task_Review', name: 'Review application', formKey: 'react:toy-review' },
    ]);
  });
});

describe('parseProcessName', () => {
  it('returns the process name attribute', () => {
    expect(parseProcessName(BPMN_XML)).toBe('Toy Registration');
  });
});

describe('parseFlowGraph', () => {
  const graph = parseFlowGraph(BPMN_XML);

  it('records every flow node with its BPMN type', () => {
    expect(graph.nodes.get('Task_Review')).toEqual({
      id: 'Task_Review',
      name: 'Review application',
      type: 'userTask',
    });
    expect(graph.nodes.get('Gateway_Decision')?.type).toBe('exclusiveGateway');
    expect(graph.nodes.has('Flow_Start')).toBe(false); // sequence flows are edges, not nodes
  });

  it('builds outgoing edges in document order', () => {
    expect(graph.outgoing.get('StartEvent_1')).toEqual(['Task_Review']);
    expect(graph.outgoing.get('Task_Review')).toEqual(['Gateway_Decision']);
    expect(graph.outgoing.get('Gateway_Decision')).toEqual([
      'EndEvent_Approved',
      'EndEvent_Rejected',
    ]);
    expect(graph.outgoing.has('EndEvent_Approved')).toBe(false);
  });

  it('resolves the default= sequence flow to its target node', () => {
    expect(graph.defaultTarget.get('Gateway_Decision')).toBe('EndEvent_Rejected');
  });
});

describe('nextSteps', () => {
  const graph = parseFlowGraph(BPMN_XML);

  it('walks from the start through the gateway, preferring the non-default flow', () => {
    const steps = nextSteps(graph, 'StartEvent_1');
    // The gateway itself is not a human-relevant step type, so it is skipped;
    // the default (rejected) branch is the fallback path, so the walk takes
    // the explicit approval flow and stops at the end event.
    expect(steps.map((s) => s.id)).toEqual(['Task_Review', 'EndEvent_Approved']);
  });

  it('respects the limit', () => {
    const steps = nextSteps(graph, 'StartEvent_1', 1);
    expect(steps.map((s) => s.id)).toEqual(['Task_Review']);
  });

  it('returns nothing when starting at an end event', () => {
    expect(nextSteps(graph, 'EndEvent_Approved')).toEqual([]);
  });
});
