import { useState, useEffect, type FormEvent } from 'react';
import type { FormProps } from '../types';
import { listVehicles, type Vehicle } from '../../api/vehicleRegistryApi';
import FileUpload, { type FileUploadValue } from '../../components/FileUpload';

/**
 * Owner form (PartA) — collects the registering owner's details, the
 * vehicle being registered, and an optional list of co-owners that must
 * co-sign the registration before it reaches Transport Authority review.
 *
 * On submit the form generates one UUID token per participant (owner plus
 * each co-owner), seeds `ownerConfirmations` with the owner's signature
 * already recorded, and writes everything as process variables. The BPMN
 * gateway downstream branches on whether `additionalOwners` is non-empty.
 *
 * If the Transport Authority reviewer or a co-owner sent the case back
 * for corrections, `sendBackReason` is set in process variables and is
 * shown to the owner as a banner. Resubmission regenerates ALL tokens so
 * old confirmation links can't be reused against the new round.
 */
export default function OwnerVehicleForm({ data, onComplete, submitting, readOnly }: FormProps) {
  const [firstName, setFirstName] = useState((data.firstName as string) ?? '');
  const [lastName, setLastName] = useState((data.lastName as string) ?? '');
  const [age, setAge] = useState(data.age != null ? String(data.age) : '');
  const [objectId, setObjectId] = useState((data.objectId as string) ?? '');
  const [applicantEmail, setApplicantEmail] = useState((data.applicantEmail as string) ?? '');

  // Additional owners come back from history as a Spin JSON value. CIB seven
  // unwraps it as either a JS array (when stored as Object) or a JSON string
  // (when stored as Json) — handle both shapes defensively.
  const [additionalOwners, setAdditionalOwners] = useState<Array<{ name: string; email: string }>>(
    () => parseAdditionalOwners(data.additionalOwners),
  );

  // ID document state. On the first submit this is always a fresh pending
  // upload. On a resubmit after sendback, data.idDocumentAttachmentId is
  // already populated by the previous round of Task_AttachIdDocument — we
  // surface it as a ready-to-download chip rather than asking the applicant
  // to re-upload. The contentType/size are best-effort fallbacks since they
  // weren't preserved as process variables on the original round.
  const [idDocument, setIdDocument] = useState<FileUploadValue | null>(() => {
    const attachmentId = data.idDocumentAttachmentId;
    if (typeof attachmentId === 'string' && attachmentId.length > 0) {
      return {
        attachmentId,
        filename: 'ID document',
        contentType: 'application/octet-stream',
        size: 0,
      };
    }
    return null;
  });

  const [products, setProducts] = useState<Vehicle[]>([]);
  const [productsError, setProductsError] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const sendBackReason = (data.sendBackReason as string) ?? '';
  const isResubmission = Boolean(sendBackReason) && !readOnly;

  useEffect(() => {
    listVehicles()
      .then(setProducts)
      .catch((e) => setProductsError(e instanceof Error ? e.message : String(e)));
  }, []);

  function addOwner() {
    setAdditionalOwners((prev) => [...prev, { name: '', email: '' }]);
  }

  function removeOwner(index: number) {
    setAdditionalOwners((prev) => prev.filter((_, i) => i !== index));
  }

  function updateOwner(index: number, field: 'name' | 'email', value: string) {
    setAdditionalOwners((prev) => prev.map((o, i) => (i === index ? { ...o, [field]: value } : o)));
  }

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
      setError('Please choose a vehicle from the registry.');
      return;
    }
    if (!idDocument || (!idDocument.pendingKey && !idDocument.attachmentId)) {
      setError('Please upload a copy of your ID card or passport.');
      return;
    }
    const trimmedEmail = applicantEmail.trim();
    const validEmail = (s: string) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(s);
    if (trimmedEmail && !validEmail(trimmedEmail)) {
      setError('Please enter a valid email address, or leave the field blank.');
      return;
    }

    const cleanedOwners = additionalOwners
      .map((o) => ({ name: o.name.trim(), email: o.email.trim() }))
      .filter((o) => o.name || o.email);

    if (cleanedOwners.length > 0) {
      if (!trimmedEmail) {
        setError('Your own email is required when adding co-owners — we send you a tracking link.');
        return;
      }
      for (const o of cleanedOwners) {
        if (!o.name) {
          setError('Every co-owner needs a name.');
          return;
        }
        if (!o.email || !validEmail(o.email)) {
          setError(`Co-owner "${o.name || '(unnamed)'}" needs a valid email address.`);
          return;
        }
      }
      const emails = cleanedOwners.map((o) => o.email.toLowerCase());
      const dupe = emails.find((e, i) => emails.indexOf(e) !== i);
      if (dupe) {
        setError(`Two co-owners share the email "${dupe}". Each owner needs a unique address.`);
        return;
      }
      if (emails.includes(trimmedEmail.toLowerCase())) {
        setError(
          'Your own email cannot also appear in the co-owner list — the applicant is already counted as an owner.',
        );
        return;
      }
    }

    // Regenerate every token on every submit. The first round and any
    // resubmit after a reject both produce fresh links — old emails stop
    // working as soon as new tokens replace them in the process variables.
    const applicantToken = crypto.randomUUID();
    const ownersWithTokens = cleanedOwners.map((o) => ({
      name: o.name,
      email: o.email,
      token: crypto.randomUUID(),
    }));
    const initialConfirmations: Record<string, { status: string; signedAt: string }> = {
      [applicantToken]: { status: 'approved', signedAt: new Date().toISOString() },
    };

    // Always write pendingIdDocument — non-null when there's a fresh upload
    // to migrate, null otherwise. Camunda's complete-task doesn't clear
    // unlisted variables, so a sendback resubmit that omitted this var
    // would leave a stale Spin Json behind and re-trigger
    // Task_AttachIdDocument with a pendingKey that's already been moved.
    // Explicitly nulling it on every submit avoids that hazard and keeps
    // Gateway_HasPendingUpload's condition trivially correct.
    const pendingIdDocumentVar = idDocument.pendingKey
      ? {
          value: JSON.stringify({
            pendingKey: idDocument.pendingKey,
            filename: idDocument.filename,
            contentType: idDocument.contentType,
          }),
          type: 'Json' as const,
        }
      : { value: null, type: 'Json' as const };

    await onComplete({
      firstName: { value: firstName.trim(), type: 'String' },
      lastName: { value: lastName.trim(), type: 'String' },
      age: { value: ageNum, type: 'Integer' },
      objectId: { value: objectId, type: 'String' },
      applicantEmail: { value: trimmedEmail, type: 'String' },
      sendBackReason: { value: '', type: 'String' },
      applicantToken: { value: applicantToken, type: 'String' },
      additionalOwners: { value: JSON.stringify(ownersWithTokens), type: 'Json' },
      ownerConfirmations: { value: JSON.stringify(initialConfirmations), type: 'Json' },
      // Reset the flags so a previous reject round doesn't bleed into this
      // submission. The receive task and gateway re-read them fresh.
      rejectedByOwner: { value: false, type: 'Boolean' },
      sentToProcess: { value: false, type: 'Boolean' },
      pendingIdDocument: pendingIdDocumentVar,
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
        {readOnly
          ? 'Read-only view of the registration you submitted. Edits are not possible while Transport Authority has the case.'
          : isResubmission
            ? 'Update the details below and resubmit the registration. New signing links will be sent to every co-owner.'
            : 'Owner form — fill in your details, choose the vehicle you are registering, and list any co-owners that must sign before the case goes to Transport Authority.'}
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
        <span className="field-label">Email (required if you list co-owners)</span>
        <input
          className="field-input"
          type="email"
          placeholder="you@example.com"
          value={applicantEmail}
          onChange={(e) => setApplicantEmail(e.target.value)}
          disabled={readOnly}
        />
      </label>

      <div className="field">
        <span className="field-label">Owner ID document (required)</span>
        <p className="field-hint">
          ID card, passport, or driving licence. PDF, JPEG, or PNG up to 10 MB. The Transport
          Authority reviewer will be able to download this file.
        </p>
        <FileUpload
          accept="application/pdf,image/jpeg,image/png"
          maxBytes={10 * 1024 * 1024}
          scope="pending"
          category="applicant-id-document"
          value={idDocument}
          onChange={setIdDocument}
          disabled={readOnly}
          label="Drop your ID document here, or click to choose"
        />
      </div>

      <label className="field">
        <span className="field-label">Vehicle (from registry)</span>
        <select
          className="field-input"
          value={objectId}
          onChange={(e) => setObjectId(e.target.value)}
          disabled={readOnly}
        >
          <option value="">— choose a vehicle —</option>
          {products.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </select>
      </label>
      {productsError && !readOnly && (
        <p className="form-error">Could not load the vehicle registry: {productsError}</p>
      )}

      <fieldset className="field-group">
        <legend className="field-label">Vehicle co-owners ({additionalOwners.length})</legend>
        <p className="field-hint">
          Each co-owner gets an email with a link to approve or reject the vehicle registration.
          Once all co-owners sign, any owner can click "Send to Transport Authority" to forward the
          case for review. Leave empty if you are the sole owner.
        </p>
        {additionalOwners.map((owner, i) => (
          <div key={i} className="owner-row">
            <input
              className="field-input owner-row-name"
              placeholder="Name"
              value={owner.name}
              onChange={(e) => updateOwner(i, 'name', e.target.value)}
              disabled={readOnly}
            />
            <input
              className="field-input owner-row-email"
              type="email"
              placeholder="owner@example.com"
              value={owner.email}
              onChange={(e) => updateOwner(i, 'email', e.target.value)}
              disabled={readOnly}
            />
            {!readOnly && (
              <button
                type="button"
                className="btn btn-link"
                onClick={() => removeOwner(i)}
                aria-label={`Remove co-owner ${i + 1}`}
              >
                Remove
              </button>
            )}
          </div>
        ))}
        {!readOnly && (
          <div className="form-actions">
            <button type="button" className="btn" onClick={addOwner}>
              + Add co-owner
            </button>
          </div>
        )}
      </fieldset>

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

/**
 * The `additionalOwners` process variable arrives via /task/{}/form-variables
 * after a sendback. Depending on how it was written, it can land as a JS
 * array (Object type → Jackson-deserialised) or as a JSON string (Json type
 * → SpinJsonNode → toString). Normalise to a plain array of {name, email}.
 */
export function parseAdditionalOwners(value: unknown): Array<{ name: string; email: string }> {
  if (!value) return [];
  let parsed: unknown = value;
  if (typeof value === 'string') {
    try {
      parsed = JSON.parse(value);
    } catch {
      return [];
    }
  }
  if (!Array.isArray(parsed)) return [];
  return parsed
    .filter((o): o is { name?: unknown; email?: unknown } => typeof o === 'object' && o !== null)
    .map((o) => ({
      name: typeof o.name === 'string' ? o.name : '',
      email: typeof o.email === 'string' ? o.email : '',
    }));
}
