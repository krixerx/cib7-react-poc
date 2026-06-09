/** A user task declared in a BPMN process definition. */
export interface UserTaskDef {
  /** BPMN element id — equals a task's `taskDefinitionKey` at runtime. */
  id: string;
  /** Display name (falls back to the id if unnamed). */
  name: string;
  /** The `camunda:formKey` attribute, e.g. "react:personal-details". */
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
