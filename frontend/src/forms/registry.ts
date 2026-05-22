import type { ComponentType } from 'react';
import type { FormProps } from './types';
import PersonalDetailsForm from './personal-details/PersonalDetailsForm';
import ReviewApplicationForm from './review-application/ReviewApplicationForm';

/**
 * Maps a logical form id to a React component. The form id is the part of the
 * BPMN `camunda:formKey` after the `react:` prefix (spec §6.1, §8.3).
 *
 * Adding a form = add its folder under src/forms/ and one entry here.
 */
export const formRegistry: Record<string, ComponentType<FormProps>> = {
  'personal-details': PersonalDetailsForm,
  'review-application': ReviewApplicationForm,
};

/** Parses a BPMN formKey ("react:personal-details") into a form id. */
export function parseFormId(formKey: string | null | undefined): string | null {
  if (!formKey) return null;
  const prefix = 'react:';
  return formKey.startsWith(prefix) ? formKey.slice(prefix.length) : formKey;
}
