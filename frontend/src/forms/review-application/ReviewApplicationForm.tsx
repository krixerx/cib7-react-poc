import type { FormProps } from '../types';

/**
 * Task 2 form — shows the data submitted in task 1 plus the `price` fetched by
 * the "Get price" service task (read-only), and completes the task with a
 * `decision` outcome. The BPMN exclusive gateway branches on `decision`.
 */
export default function ReviewApplicationForm({ data, onComplete, submitting }: FormProps) {
  function decide(decision: 'approve' | 'reject') {
    return onComplete({ decision: { value: decision, type: 'String' } });
  }

  const price =
    data.price != null && data.price !== '' ? String(data.price) : '—';

  return (
    <div className="form">
      <p className="form-intro">
        Review the submitted details and the fetched price, then approve or
        reject the application.
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

      <label className="field">
        <span className="field-label">Price (fetched by the Get price service task)</span>
        <input className="field-input" value={price} disabled readOnly />
      </label>

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
