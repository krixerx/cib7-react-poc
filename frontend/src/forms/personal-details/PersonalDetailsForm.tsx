import { useState, useEffect, type FormEvent } from 'react';
import type { FormProps } from '../types';
import { listPricedObjects, type PricedObject } from '../../api/objectsApi';

/**
 * Task 1 form — collects the applicant's personal details and the chosen
 * product, then completes the task. The selected product's id is written to
 * the `objectId` variable, which the "Get price" service task uses to call
 * https://api.restful-api.dev/objects/{objectId}.
 */
export default function PersonalDetailsForm({ data, onComplete, submitting }: FormProps) {
  const [firstName, setFirstName] = useState((data.firstName as string) ?? '');
  const [lastName, setLastName] = useState((data.lastName as string) ?? '');
  const [age, setAge] = useState(data.age != null ? String(data.age) : '');
  const [objectId, setObjectId] = useState((data.objectId as string) ?? '');

  const [products, setProducts] = useState<PricedObject[]>([]);
  const [productsError, setProductsError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
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
    });
  }

  return (
    <form className="form" onSubmit={handleSubmit}>
      <p className="form-intro">
        Applicant form — fill in the personal details and choose a product, then
        confirm to send the application into the process.
      </p>

      <label className="field">
        <span className="field-label">First name</span>
        <input
          className="field-input"
          value={firstName}
          onChange={(e) => setFirstName(e.target.value)}
          autoFocus
        />
      </label>

      <label className="field">
        <span className="field-label">Last name</span>
        <input
          className="field-input"
          value={lastName}
          onChange={(e) => setLastName(e.target.value)}
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
        />
      </label>

      <label className="field">
        <span className="field-label">Product</span>
        <select
          className="field-input"
          value={objectId}
          onChange={(e) => setObjectId(e.target.value)}
        >
          <option value="">— choose a product —</option>
          {products.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </select>
      </label>
      {productsError && (
        <p className="form-error">Could not load products: {productsError}</p>
      )}

      {error && <p className="form-error">{error}</p>}

      <div className="form-actions">
        <button type="submit" className="btn btn-primary" disabled={submitting}>
          {submitting ? 'Confirming…' : 'Confirm'}
        </button>
      </div>
    </form>
  );
}
