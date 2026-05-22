import type { FormProps } from '../types';

/**
 * Task 2 form — shows the data submitted in task 1 read-only, and completes
 * the task with a `decision` outcome. The BPMN exclusive gateway branches on
 * `decision` ("approve" routes to the approved end event).
 */
export default function ReviewApplicationForm({ data, onComplete, submitting }: FormProps) {
  function decide(decision: 'approve' | 'reject') {
    return onComplete({ decision: { value: decision, type: 'String' } });
  }

  return (
    <div className="form">
      <p className="form-intro">
        Review the submitted details, then approve or reject the application.
      </p>

      <dl className="summary">
        <div className="summary-row">
          <dt>First name</dt>
          <dd>{(data.firstName as string) ?? '—'}</dd>
        </div>
        <div className="summary-row">
          <dt>Last name</dt>
          <dd>{(data.lastName as string) ?? '—'}</dd>
        </div>
        <div className="summary-row">
          <dt>Age</dt>
          <dd>{data.age != null ? String(data.age) : '—'}</dd>
        </div>
      </dl>

      <div className="form-actions">
        <button
          className="btn btn-primary"
          disabled={submitting}
          onClick={() => decide('approve')}
        >
          {submitting ? 'Working…' : 'Approve'}
        </button>
        <button
          className="btn btn-danger"
          disabled={submitting}
          onClick={() => decide('reject')}
        >
          Reject
        </button>
      </div>
    </div>
  );
}
