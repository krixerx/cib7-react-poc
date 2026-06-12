// Variable type mapping: plain JSON values → Camunda's { value, type }
// envelope used by /engine-rest start_process and complete_task. The schema
// (when available) is authoritative because the BPMN/DMN expect specific
// engine types (Integer vs Double, Json vs String) that aren't always
// recoverable from runtime values alone.

export interface JsonSchema {
  type?: string;
  properties?: Record<string, JsonSchema>;
}

export interface CamundaVariable {
  value: unknown;
  type: string;
}

export function toCamundaVariables(
  variables: Record<string, unknown>,
  schema?: JsonSchema,
): Record<string, CamundaVariable> {
  const out: Record<string, CamundaVariable> = {};
  const props = schema?.properties ?? {};
  for (const [name, value] of Object.entries(variables)) {
    out[name] = encodeVariable(value, props[name]);
  }
  return out;
}

function encodeVariable(value: unknown, propSchema?: JsonSchema): CamundaVariable {
  const t = propSchema?.type;
  if (t === 'string') return { value, type: 'String' };
  if (t === 'integer') return { value, type: 'Integer' };
  if (t === 'number') return { value, type: 'Double' };
  if (t === 'boolean') return { value, type: 'Boolean' };
  if (t === 'array' || t === 'object') {
    return { value: JSON.stringify(value), type: 'Json' };
  }
  // No schema hint — fall back to runtime detection.
  if (typeof value === 'string') return { value, type: 'String' };
  if (typeof value === 'boolean') return { value, type: 'Boolean' };
  if (typeof value === 'number') {
    return { value, type: Number.isInteger(value) ? 'Integer' : 'Double' };
  }
  if (Array.isArray(value) || (typeof value === 'object' && value !== null)) {
    return { value: JSON.stringify(value), type: 'Json' };
  }
  return { value, type: 'String' };
}
