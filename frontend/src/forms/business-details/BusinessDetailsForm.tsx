import { useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import type { FormProps } from '../types';
import FileUpload, { type FileUploadValue } from '../../components/FileUpload';

/**
 * Founder form for businessRegistration. Collects company name, board
 * members, share capital, the applicant founder's identity + email, and
 * an optional list of *additional* co-founders that must sign the Articles
 * of Association before the case reaches the Business Register.
 *
 * The send-back loop shares this same form: when sendBackReason is present
 * and the form is not read-only, a banner with the reviewer's reason
 * appears above the fields and is cleared on the next submit.
 *
 * On submit the form generates one UUID token per participant (applicant
 * plus each additional co-founder), seeds `founderSignatures` with the
 * applicant's signature already recorded, and writes everything as process
 * variables. The BPMN gateway downstream branches on whether
 * `additionalFounders` is non-empty — sole-founder cases skip the whole
 * signing block.
 *
 * boardMembers is a separate concern (board-of-management appointment data
 * for the OÜ register entry). Co-founders are the people signing the
 * Articles of Association; they may or may not overlap with board members.
 * Mirror of PartA's owner / co-owner editor in vehicleRegistration.
 */

interface BoardMember {
  firstName: string;
  lastName: string;
  personalCode: string;
}

interface AdditionalFounder {
  name: string;
  email: string;
}

/** Drives the residency input in the founder form + the auto-approval DMN. */
type Residency = 'citizen' | 'e-resident' | 'foreign';

/** `value` is the technical string sent to the engine/DMN; `key` is the i18n key segment. */
const RESIDENCY_OPTIONS: Array<{ value: Residency; key: string }> = [
  { value: 'citizen', key: 'citizen' },
  { value: 'e-resident', key: 'eResident' },
  { value: 'foreign', key: 'foreign' },
];

/** Exported for tests (parsers.test.ts), like the other parse helpers below. */
export function normaliseResidency(raw: unknown): Residency {
  if (typeof raw !== 'string') return 'citizen';
  const lower = raw.toLowerCase();
  if (lower === 'citizen' || lower === 'e-resident' || lower === 'foreign') {
    return lower;
  }
  return 'citizen';
}

export function parseBoardMembers(raw: unknown): BoardMember[] {
  if (Array.isArray(raw)) {
    return raw.map((m) => ({
      firstName:
        typeof (m as BoardMember).firstName === 'string' ? (m as BoardMember).firstName : '',
      lastName: typeof (m as BoardMember).lastName === 'string' ? (m as BoardMember).lastName : '',
      personalCode:
        typeof (m as BoardMember).personalCode === 'string' ? (m as BoardMember).personalCode : '',
    }));
  }
  if (typeof raw === 'string' && raw.trim()) {
    try {
      const parsed = JSON.parse(raw);
      if (Array.isArray(parsed)) return parseBoardMembers(parsed);
    } catch {
      // fall through
    }
  }
  return [];
}

/**
 * Same defensive shape as vehicleRegistration's parseAdditionalOwners —
 * CIB seven returns Spin Json variables as either JS arrays or JSON
 * strings depending on the storage path; handle both.
 */
export function parseAdditionalFounders(raw: unknown): AdditionalFounder[] {
  if (!raw) return [];
  let parsed: unknown = raw;
  if (typeof raw === 'string') {
    try {
      parsed = JSON.parse(raw);
    } catch {
      return [];
    }
  }
  if (!Array.isArray(parsed)) return [];
  return parsed
    .filter((f): f is { name?: unknown; email?: unknown } => typeof f === 'object' && f !== null)
    .map((f) => ({
      name: typeof f.name === 'string' ? f.name : '',
      email: typeof f.email === 'string' ? f.email : '',
    }));
}

export function ensureCompanySuffix(name: string): string {
  const trimmed = name.trim();
  if (!trimmed) return trimmed;
  // Not \bOÜ\b: Ü is outside the regex \w class, so \b after it never
  // matches at a space or end-of-string — every resubmission round would
  // append another " OÜ". Delimit by whitespace/string edges instead.
  if (/(^|\s)OÜ($|\s)/i.test(trimmed)) return trimmed;
  return `${trimmed} OÜ`;
}

const EMPTY_MEMBER: BoardMember = { firstName: '', lastName: '', personalCode: '' };

export default function BusinessDetailsForm({ data, onComplete, submitting, readOnly }: FormProps) {
  const { t } = useTranslation('business-details');
  const [companyName, setCompanyName] = useState((data.companyName as string) ?? '');
  const [boardMembers, setBoardMembers] = useState<BoardMember[]>(() => {
    const parsed = parseBoardMembers(data.boardMembers);
    return parsed.length > 0 ? parsed : [{ ...EMPTY_MEMBER }];
  });
  const [shareCapital, setShareCapital] = useState(
    data.shareCapital != null ? String(data.shareCapital) : '2500',
  );
  const [applicantFirstName, setApplicantFirstName] = useState(
    (data.applicantFirstName as string) ?? '',
  );
  const [applicantLastName, setApplicantLastName] = useState(
    (data.applicantLastName as string) ?? '',
  );
  const [applicantAge, setApplicantAge] = useState(
    data.applicantAge != null ? String(data.applicantAge) : '',
  );
  const [applicantResidency, setApplicantResidency] = useState<Residency>(() =>
    normaliseResidency(data.applicantResidency),
  );
  const [applicantEmail, setApplicantEmail] = useState((data.applicantEmail as string) ?? '');
  const [additionalFounders, setAdditionalFounders] = useState<AdditionalFounder[]>(() =>
    parseAdditionalFounders(data.additionalFounders),
  );

  // Articles of Association state. On the first submit this is always a fresh
  // pending upload. On a resubmit after sendback, data.aoaDocumentAttachmentId
  // is already populated by the previous round of Task_AttachAoaDocument — we
  // surface it as a ready-to-download chip rather than asking the founder to
  // re-upload. The contentType/size are best-effort fallbacks since they
  // weren't preserved as process variables on the original round. Same
  // pattern as vehicleRegistration's idDocument handling.
  const [aoaDocument, setAoaDocument] = useState<FileUploadValue | null>(() => {
    const attachmentId = data.aoaDocumentAttachmentId;
    if (typeof attachmentId === 'string' && attachmentId.length > 0) {
      return {
        attachmentId,
        filename: t('fields.aoa.existingFilename'),
        contentType: 'application/octet-stream',
        size: 0,
      };
    }
    return null;
  });
  const [error, setError] = useState<string | null>(null);

  const sendBackReason = (data.sendBackReason as string) ?? '';
  const isResubmission = Boolean(sendBackReason) && !readOnly;

  function addMember() {
    setBoardMembers((prev) => [...prev, { ...EMPTY_MEMBER }]);
  }

  function removeMember(index: number) {
    setBoardMembers((prev) => {
      const next = prev.filter((_, i) => i !== index);
      return next.length > 0 ? next : [{ ...EMPTY_MEMBER }];
    });
  }

  function updateMember(index: number, field: keyof BoardMember, value: string) {
    setBoardMembers((prev) => prev.map((m, i) => (i === index ? { ...m, [field]: value } : m)));
  }

  function addFounder() {
    setAdditionalFounders((prev) => [...prev, { name: '', email: '' }]);
  }

  function removeFounder(index: number) {
    setAdditionalFounders((prev) => prev.filter((_, i) => i !== index));
  }

  function updateFounder(index: number, field: 'name' | 'email', value: string) {
    setAdditionalFounders((prev) =>
      prev.map((f, i) => (i === index ? { ...f, [field]: value } : f)),
    );
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);

    const finalCompanyName = ensureCompanySuffix(companyName);
    if (!finalCompanyName) {
      setError(t('errors.companyNameRequired'));
      return;
    }

    const cleanedMembers = boardMembers
      .map((m) => ({
        firstName: m.firstName.trim(),
        lastName: m.lastName.trim(),
        personalCode: m.personalCode.trim(),
      }))
      .filter((m) => m.firstName || m.lastName || m.personalCode);

    if (cleanedMembers.length === 0) {
      setError(t('errors.boardMemberRequired'));
      return;
    }
    for (const m of cleanedMembers) {
      if (!m.firstName || !m.lastName || !m.personalCode) {
        setError(t('errors.boardMemberIncomplete'));
        return;
      }
      if (!/^\d{11}$/.test(m.personalCode)) {
        setError(t('errors.personalCodeFormat', { code: m.personalCode }));
        return;
      }
    }

    const capitalNum = Number(shareCapital);
    if (!Number.isFinite(capitalNum) || capitalNum < 2500) {
      setError(t('errors.shareCapitalMin'));
      return;
    }

    const ageNum = Number(applicantAge);
    if (!applicantFirstName.trim() || !applicantLastName.trim()) {
      setError(t('errors.applicantNameRequired'));
      return;
    }
    if (!Number.isInteger(ageNum) || ageNum < 0 || ageNum > 130) {
      setError(t('errors.applicantAgeRange'));
      return;
    }

    if (!aoaDocument || (!aoaDocument.pendingKey && !aoaDocument.attachmentId)) {
      setError(t('errors.aoaRequired'));
      return;
    }

    const trimmedEmail = applicantEmail.trim();
    const validEmail = (s: string) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(s);
    if (trimmedEmail && !validEmail(trimmedEmail)) {
      setError(t('errors.emailInvalid'));
      return;
    }

    const cleanedFounders = additionalFounders
      .map((f) => ({ name: f.name.trim(), email: f.email.trim() }))
      .filter((f) => f.name || f.email);

    if (cleanedFounders.length > 0) {
      if (!trimmedEmail) {
        setError(t('errors.applicantEmailRequiredForFounders'));
        return;
      }
      for (const f of cleanedFounders) {
        if (!f.name) {
          setError(t('errors.founderNameRequired'));
          return;
        }
        if (!f.email || !validEmail(f.email)) {
          setError(t('errors.founderEmailInvalid', { name: f.name || t('errors.unnamedFounder') }));
          return;
        }
      }
      const emails = cleanedFounders.map((f) => f.email.toLowerCase());
      const dupe = emails.find((e, i) => emails.indexOf(e) !== i);
      if (dupe) {
        setError(t('errors.duplicateFounderEmail', { email: dupe }));
        return;
      }
      if (emails.includes(trimmedEmail.toLowerCase())) {
        setError(t('errors.applicantEmailInFounders'));
        return;
      }
    }

    // Regenerate every token on every submit. The first round and any
    // resubmit after a reject both produce fresh links — old emails stop
    // working as soon as new tokens replace them in the process variables.
    const applicantToken = crypto.randomUUID();
    const foundersWithTokens = cleanedFounders.map((f) => ({
      name: f.name,
      email: f.email,
      token: crypto.randomUUID(),
    }));
    const initialSignatures: Record<string, { status: string; signedAt: string }> = {
      [applicantToken]: { status: 'approved', signedAt: new Date().toISOString() },
    };

    // Always write pendingAoaDocument — non-null when there's a fresh upload
    // to migrate, null otherwise. Camunda's complete-task doesn't clear
    // unlisted variables, so a sendback resubmit that omitted this var
    // would leave a stale Spin Json behind and re-trigger
    // Task_AttachAoaDocument with a pendingKey that's already been moved.
    // Explicitly nulling it on every submit avoids that hazard and keeps
    // Gateway_HasPendingAoa's condition trivially correct.
    const pendingAoaDocumentVar = aoaDocument.pendingKey
      ? {
          value: JSON.stringify({
            pendingKey: aoaDocument.pendingKey,
            filename: aoaDocument.filename,
            contentType: aoaDocument.contentType,
          }),
          type: 'Json' as const,
        }
      : { value: null, type: 'Json' as const };

    await onComplete({
      companyName: { value: finalCompanyName, type: 'String' },
      boardMembers: { value: JSON.stringify(cleanedMembers), type: 'Json' },
      shareCapital: { value: capitalNum, type: 'Double' },
      applicantFirstName: { value: applicantFirstName.trim(), type: 'String' },
      applicantLastName: { value: applicantLastName.trim(), type: 'String' },
      applicantAge: { value: ageNum, type: 'Integer' },
      applicantResidency: { value: applicantResidency, type: 'String' },
      applicantEmail: { value: trimmedEmail, type: 'String' },
      // Clear the send-back reason so a future cycle doesn't show a stale banner.
      sendBackReason: { value: '', type: 'String' },
      applicantToken: { value: applicantToken, type: 'String' },
      additionalFounders: { value: JSON.stringify(foundersWithTokens), type: 'Json' },
      founderSignatures: { value: JSON.stringify(initialSignatures), type: 'Json' },
      // Reset the flags so a previous reject round doesn't bleed into this
      // submission. The receive task and gateway re-read them fresh.
      rejectedByFounder: { value: false, type: 'Boolean' },
      sentToRegister: { value: false, type: 'Boolean' },
      pendingAoaDocument: pendingAoaDocumentVar,
    });
  }

  return (
    <form className="form" onSubmit={handleSubmit}>
      {isResubmission && (
        <div className="form-banner form-banner-warn">
          <strong>{t('banner.sentBack')}</strong>
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
        <span className="field-label">{t('fields.companyName.label')}</span>
        <input
          className="field-input"
          value={companyName}
          onChange={(e) => setCompanyName(e.target.value)}
          placeholder={t('fields.companyName.placeholder')}
          disabled={readOnly}
          required
        />
      </label>

      <fieldset className="field-group">
        <legend className="field-label">{t('sections.boardMembers.legend')}</legend>
        {boardMembers.map((m, idx) => (
          <div key={idx} className="board-member-row">
            <input
              className="field-input"
              value={m.firstName}
              onChange={(e) => updateMember(idx, 'firstName', e.target.value)}
              placeholder={t('fields.boardMember.firstNamePlaceholder')}
              disabled={readOnly}
            />
            <input
              className="field-input"
              value={m.lastName}
              onChange={(e) => updateMember(idx, 'lastName', e.target.value)}
              placeholder={t('fields.boardMember.lastNamePlaceholder')}
              disabled={readOnly}
            />
            <input
              className="field-input"
              value={m.personalCode}
              onChange={(e) => updateMember(idx, 'personalCode', e.target.value)}
              placeholder={t('fields.boardMember.personalCodePlaceholder')}
              disabled={readOnly}
              inputMode="numeric"
              maxLength={11}
            />
            {!readOnly && boardMembers.length > 1 && (
              <button
                type="button"
                className="btn btn-small"
                onClick={() => removeMember(idx)}
                aria-label={t('sections.boardMembers.removeAria', { number: idx + 1 })}
              >
                ×
              </button>
            )}
          </div>
        ))}
        {!readOnly && (
          <button type="button" className="btn btn-small" onClick={addMember}>
            {t('sections.boardMembers.add')}
          </button>
        )}
      </fieldset>

      <div className="field">
        <span className="field-label">{t('fields.aoa.label')}</span>
        <p className="field-hint">{t('fields.aoa.hint')}</p>
        <FileUpload
          accept="application/pdf,image/jpeg,image/png"
          maxBytes={10 * 1024 * 1024}
          scope="pending"
          category="founder-articles-of-association"
          value={aoaDocument}
          onChange={setAoaDocument}
          disabled={readOnly}
          label={t('fields.aoa.uploadLabel')}
        />
      </div>

      <label className="field">
        <span className="field-label">{t('fields.shareCapital.label')}</span>
        <input
          className="field-input"
          type="number"
          min={2500}
          step={100}
          value={shareCapital}
          onChange={(e) => setShareCapital(e.target.value)}
          disabled={readOnly}
          required
        />
      </label>

      <label className="field">
        <span className="field-label">{t('fields.applicantFirstName.label')}</span>
        <input
          className="field-input"
          value={applicantFirstName}
          onChange={(e) => setApplicantFirstName(e.target.value)}
          disabled
        />
        <span className="field-hint muted">{t('common:identity.fromAccount')}</span>
      </label>

      <label className="field">
        <span className="field-label">{t('fields.applicantLastName.label')}</span>
        <input
          className="field-input"
          value={applicantLastName}
          onChange={(e) => setApplicantLastName(e.target.value)}
          disabled
        />
        <span className="field-hint muted">{t('common:identity.fromAccount')}</span>
      </label>

      <label className="field">
        <span className="field-label">{t('fields.applicantAge.label')}</span>
        <input
          className="field-input"
          type="number"
          min={0}
          max={130}
          value={applicantAge}
          onChange={(e) => setApplicantAge(e.target.value)}
          disabled={readOnly}
          required
        />
      </label>

      <fieldset className="field-group">
        <legend className="field-label">{t('fields.residency.legend')}</legend>
        <p className="field-hint">{t('fields.residency.hint')}</p>
        {RESIDENCY_OPTIONS.map((opt) => (
          <label key={opt.value} className="radio-row">
            <input
              type="radio"
              name="applicantResidency"
              value={opt.value}
              checked={applicantResidency === opt.value}
              onChange={() => setApplicantResidency(opt.value)}
              disabled={readOnly}
            />
            <span className="radio-body">
              <span className="radio-label">{t(`fields.residency.options.${opt.key}.label`)}</span>
              <span className="radio-hint">{t(`fields.residency.options.${opt.key}.hint`)}</span>
            </span>
          </label>
        ))}
      </fieldset>

      <label className="field">
        <span className="field-label">{t('fields.applicantEmail.label')}</span>
        <input
          className="field-input"
          type="email"
          placeholder={t('fields.applicantEmail.placeholder')}
          value={applicantEmail}
          onChange={(e) => setApplicantEmail(e.target.value)}
          disabled
        />
        <span className="field-hint muted">{t('common:identity.fromAccount')}</span>
      </label>

      <fieldset className="field-group">
        <legend className="field-label">
          {t('sections.coFounders.legend', { total: additionalFounders.length })}
        </legend>
        <p className="field-hint">{t('sections.coFounders.hint')}</p>
        {additionalFounders.map((founder, i) => (
          <div key={i} className="owner-row">
            <input
              className="field-input owner-row-name"
              placeholder={t('fields.founder.namePlaceholder')}
              value={founder.name}
              onChange={(e) => updateFounder(i, 'name', e.target.value)}
              disabled={readOnly}
            />
            <input
              className="field-input owner-row-email"
              type="email"
              placeholder={t('fields.founder.emailPlaceholder')}
              value={founder.email}
              onChange={(e) => updateFounder(i, 'email', e.target.value)}
              disabled={readOnly}
            />
            {!readOnly && (
              <button
                type="button"
                className="btn btn-link"
                onClick={() => removeFounder(i)}
                aria-label={t('sections.coFounders.removeAria', { number: i + 1 })}
              >
                {t('common:actions.remove')}
              </button>
            )}
          </div>
        ))}
        {!readOnly && (
          <div className="form-actions">
            <button type="button" className="btn" onClick={addFounder}>
              {t('sections.coFounders.add')}
            </button>
          </div>
        )}
      </fieldset>

      {error && <p className="form-error">{error}</p>}

      {!readOnly && (
        <div className="form-actions">
          <button type="submit" className="btn btn-primary" disabled={submitting}>
            {submitting
              ? t('common:feedback.submitting')
              : isResubmission
                ? t('actions.resubmit')
                : t('common:actions.submit')}
          </button>
        </div>
      )}
    </form>
  );
}
