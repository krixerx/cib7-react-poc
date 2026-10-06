import { createElement, type ComponentType } from 'react';
import { formRegistry } from './registry';
import SchemaForm from './schema/SchemaForm';
import type { FormProps } from './types';

const schemaForms = new Map<string, ComponentType<FormProps>>();

/**
 * The component that draws a form id: the generated TSX form when the
 * registry has one (the rare escape hatch), otherwise the schema renderer
 * bound to that id, which loads the pack's `/pack/forms/<id>.json`. A missing
 * or invalid definition is reported by the renderer itself.
 *
 * The bound component is cached per id so React sees a stable component type
 * across renders and keeps the form's state.
 */
export function formFor(formId: string): ComponentType<FormProps> {
  const tsx = formRegistry[formId];
  if (tsx) return tsx;
  let bound = schemaForms.get(formId);
  if (!bound) {
    bound = (props: FormProps) => createElement(SchemaForm, { ...props, formId });
    schemaForms.set(formId, bound);
  }
  return bound;
}
