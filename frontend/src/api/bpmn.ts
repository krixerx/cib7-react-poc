/** A user task declared in a BPMN process definition. */
export interface UserTaskDef {
  /** BPMN element id — equals a task's `taskDefinitionKey` at runtime. */
  id: string;
  /** Display name (falls back to the id if unnamed). */
  name: string;
}

/**
 * Extracts the user tasks (id + name, in document order) from BPMN XML.
 *
 * Namespace-prefix agnostic: matches `<bpmn:userTask>`, `<userTask>`, etc. by
 * comparing `localName`, so it works regardless of the BPMN exporter.
 */
export function parseUserTasks(bpmnXml: string): UserTaskDef[] {
  const doc = new DOMParser().parseFromString(bpmnXml, 'application/xml');
  return Array.from(doc.getElementsByTagName('*'))
    .filter((el) => el.localName === 'userTask')
    .map((el) => ({
      id: el.getAttribute('id') ?? '',
      name: el.getAttribute('name') || el.getAttribute('id') || '(unnamed task)',
    }));
}
