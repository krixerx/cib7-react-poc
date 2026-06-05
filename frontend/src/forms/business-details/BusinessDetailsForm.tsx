import { useState, type FormEvent } from 'react';
import type { FormProps } from '../types';

/**
 * Applicant form for businessRegistration. Collects company name, board
 * members, share capital, and the applicant's own identity. The send-back
 * loop shares this same form: when sendBackReason is present and the form
 * is not read-only, a banner with the reason appears above the fields and
 * is cleared on the next submit.
 *
 * boardMembers is a list of `{firstName, lastName, personalCode}` rendered
 * as repeating rows. The form requires at least one row; if the user
 * removes the last row, an empty row is silently re-added.
 */

interface BoardMember {
  firstName: string;
  lastName: string;
  personalCode: string;
}

function parseBoardMembers(raw: unknown): BoardMember[] {
  if (Array.isArray(raw)) {
    return raw.map((m) => ({
      firstName: typeof (m as BoardMember).firstName === 'string' ? (m as BoardMember).firstName : '',
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

function ensureCompanySuffix(name: string): string {
  const trimmed = name.trim();
  if (!trimmed) return trimmed;
  if (/\bOÜ\b/i.test(trimmed)) return trimmed;
  return `${trimmed} OÜ`;
}

const EMPTY_MEMBER: BoardMember = { firstName: '', lastName: '', personalCode: '' };

export default function BusinessDetailsForm({
  data,
  onComplete,
  submitting,
  readOnly,
}: FormProps) {
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
    setBoardMembers((prev) =>
      prev.map((m, i) => (i === index ? { ...m, [field]: value } : m)),
    );
  }

  async function handleSubmit(e: FormEvent) {
    e.preventDefault();
    setError(null);

    const finalCompanyName = ensureCompanySuffix(companyName);
    if (!finalCompanyName) {
      setError('Company name is required.');
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
      setError('At least one board member is required.');
      return;
    }
    for (const m of cleanedMembers) {
      if (!m.firstName || !m.lastName || !m.personalCode) {
        setError('Every board member needs first name, last name, and a personal code.');
        return;
      }
      if (!/^\d{11}$/.test(m.personalCode)) {
        setError(`Personal code "${m.personalCode}" must be 11 digits.`);
        return;
      }
    }

    const capitalNum = Number(shareCapital);
    if (!Number.isFinite(capitalNum) || capitalNum < 2500) {
      setError('Share capital must be a number >= 2500.');
      return;
    }

    const ageNum = Number(applicantAge);
    if (!applicantFirstName.trim() || !applicantLastName.trim()) {
      setError('Your first name and last name are required.');
      return;
    }
    if (!Number.isInteger(ageNum) || ageNum < 0 || ageNum > 130) {
      setError('Your age must be a whole number between 0 and 130.');
      return;
    }

    await onComplete({
      companyName: { value: finalCompanyName, type: 'String' },
      boardMembers: { value: JSON.stringify(cleanedMembers), type: 'Json' },
      shareCapital: { value: capitalNum, type: 'Double' },
      applicantFirstName: { value: applicantFirstName.trim(), type: 'String' },
      applicantLastName: { value: applicantLastName.trim(), type: 'String' },
      applicantAge: { value: ageNum, type: 'Integer' },
      // Clear the send-back reason so a future cycle doesn't show a stale banner.
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
        {readOnly
          ? 'A read-only view of the submitted business registration.'
          : isResubmission
          ? 'Update the details below and resubmit the application.'
          : 'Register a new private limited company (OÜ). Fill in the company details and at least one board member, then submit.'}
      </p>

      <label className="field">
        <span className="field-label">Company name</span>
        <input
          className="field-input"
          value={companyName}
          onChange={(e) => setCompanyName(e.target.value)}
          placeholder="Acme OÜ"
          disabled={readOnly}
          required
        />
      </label>

      <fieldset className="field-group">
        <legend className="field-label">Board members</legend>
        {boardMembers.map((m, idx) => (
          <div key={idx} className="board-member-row">
            <input
              className="field-input"
              value={m.firstName}
              onChange={(e) => updateMember(idx, 'firstName', e.target.value)}
              placeholder="First name"
              disabled={readOnly}
            />
            <input
              className="field-input"
              value={m.lastName}
              onChange={(e) => updateMember(idx, 'lastName', e.target.value)}
              placeholder="Last name"
              disabled={readOnly}
            />
            <input
              className="field-input"
              value={m.personalCode}
              onChange={(e) => updateMember(idx, 'personalCode', e.target.value)}
              placeholder="Personal code (11 digits)"
              disabled={readOnly}
              inputMode="numeric"
              maxLength={11}
            />
            {!readOnly && boardMembers.length > 1 && (
              <button
                type="button"
                className="btn btn-small"
                onClick={() => removeMember(idx)}
                aria-label={`Remove board member ${idx + 1}`}
              >
                ×
              </button>
            )}
          </div>
        ))}
        {!readOnly && (
          <button type="button" className="btn btn-small" onClick={addMember}>
            + Add member
          </button>
        )}
      </fieldset>

      <label className="field">
        <span className="field-label">Share capital (EUR)</span>
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
        <span className="field-label">Your first name</span>
        <input
          className="field-input"
          value={applicantFirstName}
          onChange={(e) => setApplicantFirstName(e.target.value)}
          disabled={readOnly}
          required
        />
      </label>

      <label className="field">
        <span className="field-label">Your last name</span>
        <input
          className="field-input"
          value={applicantLastName}
          onChange={(e) => setApplicantLastName(e.target.value)}
          disabled={readOnly}
          required
        />
      </label>

      <label className="field">
        <span className="field-label">Your age</span>
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

      {error && <p className="form-error">{error}</p>}

      {!readOnly && (
        <div className="form-actions">
          <button type="submit" className="btn btn-primary" disabled={submitting}>
            {submitting ? 'Submitting…' : 'Submit'}
          </button>
        </div>
      )}
    </form>
  );
}
