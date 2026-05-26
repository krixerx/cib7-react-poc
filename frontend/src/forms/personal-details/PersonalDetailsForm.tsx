import { useState, useEffect, type FormEvent } from 'react';
import type { FormProps } from '../types';
import { listPricedObjects, type PricedObject } from '../../api/objectsApi';

/**
 * Applicant form (PartA) — collects personal details and the chosen product,
 * then completes the task. The selected product's id is written to the
 * `objectId` variable so the "Get price" service task can fetch its price.
 *
 * If the civil servant sent the case back for corrections, `sendBackReason`
 * is set in process variables and is shown to the applicant as a banner.
 */
export default function PersonalDetailsForm({
  data,
  onComplete,
  submitting,
  readOnly,
}: FormProps) {
  const [firstName, setFirstName] = useState((data.firstName as string) ?? '');
  const [lastName, setLastName] = useState((data.lastName as string) ?? '');
  const [age, setAge] = useState(data.age != null ? String(data.age) : '');
  const [objectId, setObjectId] = useState((data.objectId as string) ?? '');

  const [products, setProducts] = useState<PricedObject[]>([]);
  const [productsError, setProductsError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  // Present only when the civil servant has sent the case back. The variable
  // sticks around on subsequent task completions; the form clears it on
  // re-submit so an "old" reason doesn't show on a future cycle.
  const sendBackReason = (data.sendBackReason as string) ?? '';
  const isResubmission = Boolean(sendBackReason) && !readOnly;

  useEffect(() => {
    // In read-only mode we still fetch products so the <select> can show the
    // chosen product's name rather than its raw id.
    listPricedObjects()
      .then(setProducts)
      .catch((e) => setProductsError(e instanceof Error ? e.message : String(e)));
  }, []);

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);

    const ageNum = Number(age);
    if (!firstName.trim() || !lastName.trim()) {
      setError('First name and last name are required.');
      return;
    }
    if (!Number.isInteger(ageNum) || ageNum < 1 || ageNum > 130) {
      setError('Age must be a whole number between 1 and 130.');
      return;
    }
    if (!objectId) {
      setError('Please choose a product.');
      return;
    }

    await onComplete({
      firstName: { value: firstName.trim(), type: 'String' },
      lastName: { value: lastName.trim(), type: 'String' },
      age: { value: ageNum, type: 'Integer' },
      objectId: { value: objectId, type: 'String' },
      // Clear the send-back reason once the applicant resubmits — keeping it
      // would make the next review cycle still look "sent back".
      sendBackReason: { value: '', type: 'String' },
    });
  }

  return (
    <form className="form" onSubmit={handleSubmit}>
      {isResubmission && (
        <div className="form-banner form-banner-warn">
          <strong>Sent back for corrections.</strong>
          <p className="form-banner-body">{sendBackReason}</p>
        </div>
      )}

      <p className="form-intro">
        {isResubmission
          ? 'Update the details below and resubmit the application.'
          : 'Applicant form — fill in the personal details and choose a product, then confirm to send the application into the process.'}
      </p>

      <label className="field">
        <span className="field-label">First name</span>
        <input
          className="field-input"
          value={firstName}
          onChange={(e) => setFirstName(e.target.value)}
          autoFocus={!readOnly}
          disabled={readOnly}
        />
      </label>

      <label className="field">
        <span className="field-label">Last name</span>
        <input
          className="field-input"
          value={lastName}
          onChange={(e) => setLastName(e.target.value)}
          disabled={readOnly}
        />
      </label>

      <label className="field">
        <span className="field-label">Age</span>
        <input
          className="field-input"
          type="number"
          min={1}
          max={130}
          value={age}
          onChange={(e) => setAge(e.target.value)}
          disabled={readOnly}
        />
      </label>

      <label className="field">
        <span className="field-label">Product</span>
        <select
          className="field-input"
          value={objectId}
          onChange={(e) => setObjectId(e.target.value)}
          disabled={readOnly}
        >
          <option value="">— choose a product —</option>
          {products.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </select>
      </label>
      {productsError && !readOnly && (
        <p className="form-error">Could not load products: {productsError}</p>
      )}

      {error && <p className="form-error">{error}</p>}

      {!readOnly && (
        <div className="form-actions">
          <button type="submit" className="btn btn-primary" disabled={submitting}>
            {submitting ? 'Confirming…' : isResubmission ? 'Resubmit' : 'Confirm'}
          </button>
        </div>
      )}
    </form>
  );
}
