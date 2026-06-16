import { useState, useEffect, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
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
  const { t } = useTranslation('owner-vehicle');
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
        filename: t('fields.idDocument.existingFilename'),
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
      setError(t('errors.namesRequired'));
      return;
    }
    if (!Number.isInteger(ageNum) || ageNum < 1 || ageNum > 130) {
      setError(t('errors.ageRange'));
      return;
    }
    if (!objectId) {
      setError(t('errors.vehicleRequired'));
      return;
    }
    if (!idDocument || (!idDocument.pendingKey && !idDocument.attachmentId)) {
      setError(t('errors.idDocumentRequired'));
      return;
    }
    const trimmedEmail = applicantEmail.trim();
    const validEmail = (s: string) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(s);
    if (trimmedEmail && !validEmail(trimmedEmail)) {
      setError(t('errors.invalidEmail'));
      return;
    }

    const cleanedOwners = additionalOwners
      .map((o) => ({ name: o.name.trim(), email: o.email.trim() }))
      .filter((o) => o.name || o.email);

    if (cleanedOwners.length > 0) {
      if (!trimmedEmail) {
        setError(t('errors.applicantEmailRequiredWithCoOwners'));
        return;
      }
      for (const o of cleanedOwners) {
        if (!o.name) {
          setError(t('errors.coOwnerNameRequired'));
          return;
        }
        if (!o.email || !validEmail(o.email)) {
          setError(t('errors.coOwnerEmailInvalid', { name: o.name || t('errors.coOwnerUnnamed') }));
          return;
        }
      }
      const emails = cleanedOwners.map((o) => o.email.toLowerCase());
      const dupe = emails.find((e, i) => emails.indexOf(e) !== i);
      if (dupe) {
        setError(t('errors.duplicateCoOwnerEmail', { email: dupe }));
        return;
      }
      if (emails.includes(trimmedEmail.toLowerCase())) {
        setError(t('errors.applicantEmailInCoOwners'));
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
          <strong>{t('banner.sentBackTitle')}</strong>
          <p className="form-banner-body">{sendBackReason}</p>
        </div>
      )}

      <p className="form-intro">
        {readOnly
          ? t('intro.readOnly')
          : isResubmission
            ? t('intro.resubmission')
            : t('intro.default')}
      </p>

      <label className="field">
        <span className="field-label">{t('fields.firstName.label')}</span>
        <input
          className="field-input"
          value={firstName}
          onChange={(e) => setFirstName(e.target.value)}
          disabled
        />
        <span className="field-hint muted">{t('common:identity.fromAccount')}</span>
      </label>

      <label className="field">
        <span className="field-label">{t('fields.lastName.label')}</span>
        <input
          className="field-input"
          value={lastName}
          onChange={(e) => setLastName(e.target.value)}
          disabled
        />
        <span className="field-hint muted">{t('common:identity.fromAccount')}</span>
      </label>

      <label className="field">
        <span className="field-label">{t('fields.age.label')}</span>
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
        <span className="field-label">{t('fields.email.label')}</span>
        <input
          className="field-input"
          type="email"
          placeholder={t('fields.email.placeholder')}
          value={applicantEmail}
          onChange={(e) => setApplicantEmail(e.target.value)}
          disabled
        />
        <span className="field-hint muted">{t('common:identity.fromAccount')}</span>
      </label>

      <div className="field">
        <span className="field-label">{t('fields.idDocument.label')}</span>
        <p className="field-hint">{t('fields.idDocument.hint')}</p>
        <FileUpload
          accept="application/pdf,image/jpeg,image/png"
          maxBytes={10 * 1024 * 1024}
          scope="pending"
          category="applicant-id-document"
          value={idDocument}
          onChange={setIdDocument}
          disabled={readOnly}
          label={t('fields.idDocument.dropLabel')}
        />
      </div>

      <label className="field">
        <span className="field-label">{t('fields.vehicle.label')}</span>
        <select
          className="field-input"
          value={objectId}
          onChange={(e) => setObjectId(e.target.value)}
          disabled={readOnly}
        >
          <option value="">{t('fields.vehicle.placeholder')}</option>
          {products.map((p) => (
            <option key={p.id} value={p.id}>
              {p.name}
            </option>
          ))}
        </select>
      </label>
      {productsError && !readOnly && (
        <p className="form-error">{t('errors.registryLoadFailed', { message: productsError })}</p>
      )}

      <fieldset className="field-group">
        <legend className="field-label">
          {t('sections.coOwners.legend', { count: additionalOwners.length })}
        </legend>
        <p className="field-hint">{t('sections.coOwners.hint')}</p>
        {additionalOwners.map((owner, i) => (
          <div key={i} className="owner-row">
            <input
              className="field-input owner-row-name"
              placeholder={t('fields.coOwnerName.placeholder')}
              value={owner.name}
              onChange={(e) => updateOwner(i, 'name', e.target.value)}
              disabled={readOnly}
            />
            <input
              className="field-input owner-row-email"
              type="email"
              placeholder={t('fields.coOwnerEmail.placeholder')}
              value={owner.email}
              onChange={(e) => updateOwner(i, 'email', e.target.value)}
              disabled={readOnly}
            />
            {!readOnly && (
              <button
                type="button"
                className="btn btn-link"
                onClick={() => removeOwner(i)}
                aria-label={t('actions.removeCoOwnerAria', { index: i + 1 })}
              >
                {t('common:actions.remove')}
              </button>
            )}
          </div>
        ))}
        {!readOnly && (
          <div className="form-actions">
            <button type="button" className="btn" onClick={addOwner}>
              {t('actions.addCoOwner')}
            </button>
          </div>
        )}
      </fieldset>

      {error && <p className="form-error">{error}</p>}

      {!readOnly && (
        <div className="form-actions">
          <button type="submit" className="btn btn-primary" disabled={submitting}>
            {submitting
              ? t('actions.confirming')
              : isResubmission
                ? t('actions.resubmit')
                : t('actions.confirm')}
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
