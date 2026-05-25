import type { CamundaTask, CamundaVariables } from '../api/camundaClient';

/**
 * Props every task form receives.
 *
 * The spec (§8.2) splits this into separate edit/view contracts. For a
 * two-task POC a single contract is enough: the "review" form simply renders
 * its known fields read-only and still completes the task with an outcome.
 *
 * When `readOnly` is true the form is being shown for a finished process — it
 * should display `data` but disable all inputs and submit actions.
 */
export interface FormProps {
  /** The task being rendered. */
  task: CamundaTask;
  /** Current process variables, unwrapped from `{value,type}` to plain values. */
  data: Record<string, unknown>;
  /** Completes the task with the given CIB seven typed variables. */
  onComplete: (variables: CamundaVariables) => Promise<void>;
  /** True while a completion request is in flight. */
  submitting: boolean;
  /** When true, render the form as a read-only history view (no submit). */
  readOnly?: boolean;
}
