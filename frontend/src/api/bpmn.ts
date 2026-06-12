/** A user task declared in a BPMN process definition. */
export interface UserTaskDef {
  /** BPMN element id — equals a task's `taskDefinitionKey` at runtime. */
  id: string;
  /** Display name (falls back to the id if unnamed). */
  name: string;
  /** The `camunda:formKey` attribute, e.g. "react:owner-vehicle". */
  formKey: string | null;
}

const CAMUNDA_NS = 'http://camunda.org/schema/1.0/bpmn';

/**
 * Extracts the user tasks (id + name + formKey, in document order) from
 * BPMN XML.
 *
 * Namespace-prefix agnostic: matches `<bpmn:userTask>`, `<userTask>`, etc. by
 * comparing `localName`, so it works regardless of the BPMN exporter. The
 * `formKey` attribute lives in the camunda namespace; we try namespaced lookup
 * first, then fall back to the literal qualified name.
 */
export function parseUserTasks(bpmnXml: string): UserTaskDef[] {
  const doc = new DOMParser().parseFromString(bpmnXml, 'application/xml');
  return Array.from(doc.getElementsByTagName('*'))
    .filter((el) => el.localName === 'userTask')
    .map((el) => ({
      id: el.getAttribute('id') ?? '',
      name: el.getAttribute('name') || el.getAttribute('id') || '(unnamed task)',
      formKey:
        el.getAttributeNS(CAMUNDA_NS, 'formKey') ||
        el.getAttribute('camunda:formKey') ||
        null,
    }));
}

/**
 * Returns the human-readable name of the `<bpmn:process>` element, falling
 * back to its id when name is missing. Used to title case-detail pages with
 * the service name instead of the technical process key.
 */
export function parseProcessName(bpmnXml: string): string | null {
  const doc = new DOMParser().parseFromString(bpmnXml, 'application/xml');
  const process = Array.from(doc.getElementsByTagName('*')).find(
    (el) => el.localName === 'process',
  );
  if (!process) return null;
  return process.getAttribute('name') || process.getAttribute('id') || null;
}

/**
 * Builds an `id → name` map for every element in the BPMN that carries both an
 * `id` and a `name` attribute (tasks, gateways, events, …). Used to resolve an
 * incident's `activityId` to a human-readable label.
 */
export function parseActivityNames(bpmnXml: string): Map<string, string> {
  const doc = new DOMParser().parseFromString(bpmnXml, 'application/xml');
  const out = new Map<string, string>();
  for (const el of Array.from(doc.getElementsByTagName('*'))) {
    const id = el.getAttribute('id');
    const name = el.getAttribute('name');
    if (id && name) out.set(id, name);
  }
  return out;
}

/** One flow node of the parsed BPMN graph. */
export interface FlowNode {
  id: string;
  name: string | null;
  /** localName of the element — "userTask", "exclusiveGateway", "endEvent", … */
  type: string;
}

/** Sequence-flow graph of a BPMN model, for walking "what comes next". */
export interface FlowGraph {
  nodes: Map<string, FlowNode>;
  /** node id → target node ids of its outgoing sequence flows, in document order. */
  outgoing: Map<string, string[]>;
  /** node id → target node id of its `default=` sequence flow, when declared. */
  defaultTarget: Map<string, string>;
  /** node id → enclosing subProcess id, for elements nested in a subprocess. */
  parentSubProcess: Map<string, string>;
}

/** Node types worth showing to a human when listing upcoming steps. */
const STEP_TYPES = new Set([
  'userTask',
  'serviceTask',
  'sendTask',
  'receiveTask',
  'businessRuleTask',
  'scriptTask',
  'callActivity',
  'subProcess',
  'endEvent',
]);

/**
 * Parses the sequence-flow graph out of BPMN XML. Powers the case-progress
 * timeline's "expected next steps": from the activity a case is currently
 * parked on, walk forward through the model to show what is still ahead.
 */
export function parseFlowGraph(bpmnXml: string): FlowGraph {
  const doc = new DOMParser().parseFromString(bpmnXml, 'application/xml');
  const all = Array.from(doc.getElementsByTagName('*'));

  const nodes = new Map<string, FlowNode>();
  const outgoing = new Map<string, string[]>();
  const flowTarget = new Map<string, string>(); // sequenceFlow id → target node id
  const defaultFlowOf = new Map<string, string>(); // node id → sequenceFlow id
  const parentSubProcess = new Map<string, string>();

  for (const el of all) {
    const id = el.getAttribute('id');
    if (!id) continue;

    if (el.localName === 'sequenceFlow') {
      const source = el.getAttribute('sourceRef');
      const target = el.getAttribute('targetRef');
      if (source && target) {
        flowTarget.set(id, target);
        const list = outgoing.get(source);
        if (list) list.push(target);
        else outgoing.set(source, [target]);
      }
      continue;
    }

    nodes.set(id, { id, name: el.getAttribute('name'), type: el.localName });
    const def = el.getAttribute('default');
    if (def) defaultFlowOf.set(id, def);

    // Nearest enclosing subProcess, so the walker can escape a subprocess
    // interior (inner end events have no outgoing flow) by continuing from
    // the subprocess element itself.
    let parent = el.parentElement;
    while (parent) {
      if (parent.localName === 'subProcess') {
        parentSubProcess.set(id, parent.getAttribute('id') ?? '');
        break;
      }
      parent = parent.parentElement;
    }
  }

  const defaultTarget = new Map<string, string>();
  for (const [nodeId, flowId] of defaultFlowOf) {
    const target = flowTarget.get(flowId);
    if (target) defaultTarget.set(nodeId, target);
  }

  return { nodes, outgoing, defaultTarget, parentSubProcess };
}

/**
 * Walks forward from `fromActivityId` and returns up to `limit` upcoming
 * human-relevant steps (named tasks / wait states / end events).
 *
 * Branching gateways are resolved heuristically: prefer the first
 * NON-default outgoing flow (BPMN convention puts the explicit condition on
 * the main path and `default=` on the fallback — e.g. the review gateway's
 * default is the send-back loop, the conditional flow is approval). The
 * result is therefore *one likely path*, not a guarantee — the UI labels it
 * accordingly. Subprocesses are treated as a single named step.
 */
export function nextSteps(graph: FlowGraph, fromActivityId: string, limit = 5): FlowNode[] {
  const steps: FlowNode[] = [];
  const visited = new Set<string>();
  let current: string | undefined = fromActivityId;
  let hops = 0;

  while (current && steps.length < limit && hops < 60) {
    hops += 1;

    const targets: string[] | undefined = graph.outgoing.get(current);
    if (!targets || targets.length === 0) {
      // Dead end — if we're inside a subprocess (inner end event), continue
      // from the subprocess element itself; otherwise we're done.
      const parent: string | undefined = graph.parentSubProcess.get(current);
      if (!parent || visited.has(parent)) break;
      visited.add(parent);
      current = parent;
      continue;
    }

    let next: string;
    if (targets.length === 1) {
      next = targets[0];
    } else {
      const def = graph.defaultTarget.get(current);
      next = targets.find((t) => t !== def) ?? targets[0];
    }

    if (visited.has(next)) break; // loop (e.g. send-back) — stop rather than spin
    visited.add(next);
    current = next;

    const node = graph.nodes.get(next);
    if (node && node.name && STEP_TYPES.has(node.type)) {
      steps.push(node);
      if (node.type === 'endEvent') break;
      // Subprocess shown as one step — skip its interior by continuing from
      // the subprocess element's own outgoing flow (handled naturally, since
      // `outgoing` for the subprocess id holds the parent-level flow).
    }
  }

  return steps;
}
