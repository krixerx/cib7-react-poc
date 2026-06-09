import { useEffect, useRef, useState, type DragEvent, type ChangeEvent } from 'react';
import {
  requestUploadUrl,
  uploadToPresigned,
  getDownloadUrl,
  DocumentsApiError,
  type DocumentCategory,
} from '../api/documentsApi';

/**
 * Tracks one ID/document upload across the lifetime of a form.
 *
 *  - Fresh upload, before the BPMN sees it:
 *      { pendingKey, filename, contentType, size }
 *  - Pre-existing attachment (sendback resubmit, kept the existing file):
 *      { attachmentId, filename, contentType, size }
 *
 * Mutually exclusive in practice; the type allows both because that's how
 * the data flows through React state during a replace.
 */
export interface FileUploadValue {
  pendingKey?: string;
  attachmentId?: string;
  filename: string;
  contentType: string;
  size: number;
}

interface Props {
  accept: string;
  maxBytes: number;
  scope: 'pending' | 'process';
  scopeId?: string;
  category: DocumentCategory;
  value: FileUploadValue | null;
  onChange: (value: FileUploadValue | null) => void;
  disabled?: boolean;
  label?: string;
}

/**
 * Drag-and-drop file picker that uploads straight to RustFS via a presigned
 * URL minted by the backend. Renders three distinct states:
 *
 *   - empty       : dropzone, click or drag-drop a file
 *   - uploading   : filename + progress bar
 *   - attached    : filename chip + Remove (and Download if attachmentId is set)
 *
 * Keeps no I/O when `disabled` — used for the read-only view of submitted
 * forms, which should still show what was uploaded but never let the user
 * change it.
 */
export default function FileUpload({
  accept,
  maxBytes,
  scope,
  scopeId,
  category: _category,
  value,
  onChange,
  disabled,
  label,
}: Props) {
  void _category;
  const inputRef = useRef<HTMLInputElement>(null);
  const [progress, setProgress] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [dragging, setDragging] = useState(false);

  // Reset progress whenever `value` changes from outside — e.g. parent
  // cleared after a successful submit.
  useEffect(() => {
    if (value === null) {
      setProgress(null);
      setError(null);
    }
  }, [value]);

  function pickFile() {
    if (disabled || progress !== null) return;
    inputRef.current?.click();
  }

  function handleDrop(e: DragEvent<HTMLDivElement>) {
    e.preventDefault();
    setDragging(false);
    if (disabled || progress !== null) return;
    const file = e.dataTransfer.files?.[0];
    if (file) void doUpload(file);
  }

  function handleInputChange(e: ChangeEvent<HTMLInputElement>) {
    const file = e.target.files?.[0];
    // Reset the input so picking the same file twice (e.g. after Remove) still fires.
    e.target.value = '';
    if (file) void doUpload(file);
  }

  async function doUpload(file: File) {
    setError(null);
    if (!accept.split(',').map((s) => s.trim()).includes(file.type)) {
      setError(`Unsupported file type "${file.type}". Allowed: ${accept}.`);
      return;
    }
    if (file.size > maxBytes) {
      setError(`File too large (${formatBytes(file.size)}). Maximum is ${formatBytes(maxBytes)}.`);
      return;
    }
    setProgress(0);
    try {
      const u = await requestUploadUrl({
        filename: file.name,
        contentType: file.type,
        size: file.size,
        scope,
        scopeId,
      });
      await uploadToPresigned(u.url, u.headers, file, setProgress);
      onChange({
        pendingKey: u.key,
        filename: file.name,
        contentType: file.type,
        size: file.size,
      });
    } catch (e) {
      const msg = e instanceof DocumentsApiError ? e.message : String(e);
      setError(msg);
    } finally {
      setProgress(null);
    }
  }

  async function handleDownload() {
    if (!value?.attachmentId) return;
    try {
      const d = await getDownloadUrl(value.attachmentId);
      window.open(d.url, '_blank', 'noopener,noreferrer');
    } catch (e) {
      const msg = e instanceof DocumentsApiError ? e.message : String(e);
      setError(msg);
    }
  }

  function handleRemove() {
    if (disabled) return;
    onChange(null);
    setError(null);
  }

  if (value) {
    // Attached state — either a fresh pending upload (chip + Remove) or an
    // already-migrated attachment from a previous round (chip + Download + Replace).
    return (
      <div className="file-upload-attached">
        <div className="file-upload-chip">
          <span className="file-upload-chip-name">{value.filename}</span>
          <span className="file-upload-chip-size">{formatBytes(value.size)}</span>
        </div>
        {!disabled && (
          <div className="file-upload-actions">
            {value.attachmentId && (
              <button type="button" className="btn btn-link" onClick={handleDownload}>
                Download
              </button>
            )}
            <button type="button" className="btn btn-link" onClick={pickFile}>
              Replace
            </button>
            <button type="button" className="btn btn-link" onClick={handleRemove}>
              Remove
            </button>
          </div>
        )}
        {disabled && value.attachmentId && (
          <div className="file-upload-actions">
            <button type="button" className="btn btn-link" onClick={handleDownload}>
              Download
            </button>
          </div>
        )}
        <input
          ref={inputRef}
          type="file"
          accept={accept}
          onChange={handleInputChange}
          style={{ display: 'none' }}
        />
        {error && <p className="form-error">{error}</p>}
      </div>
    );
  }

  return (
    <div className="file-upload-wrap">
      <div
        className={`file-upload-zone${dragging ? ' file-upload-zone-active' : ''}${
          disabled ? ' file-upload-zone-disabled' : ''
        }`}
        onClick={pickFile}
        onDragOver={(e) => {
          e.preventDefault();
          if (!disabled && progress === null) setDragging(true);
        }}
        onDragLeave={() => setDragging(false)}
        onDrop={handleDrop}
        role="button"
        tabIndex={disabled ? -1 : 0}
        onKeyDown={(e) => {
          if ((e.key === 'Enter' || e.key === ' ') && !disabled) pickFile();
        }}
      >
        {progress === null ? (
          <>
            <div className="file-upload-title">
              {label ?? 'Drop a file here, or click to choose'}
            </div>
            <div className="file-upload-hint">
              {accept.replace(/application\//g, '').replace(/image\//g, '')} &middot; up to {formatBytes(maxBytes)}
            </div>
          </>
        ) : (
          <div className="file-upload-progress-wrap">
            <div className="file-upload-progress">
              <div
                className="file-upload-progress-bar"
                style={{ width: `${Math.round(progress * 100)}%` }}
              />
            </div>
            <div className="file-upload-hint">Uploading… {Math.round(progress * 100)}%</div>
          </div>
        )}
      </div>
      <input
        ref={inputRef}
        type="file"
        accept={accept}
        onChange={handleInputChange}
        style={{ display: 'none' }}
      />
      {error && <p className="form-error">{error}</p>}
    </div>
  );
}

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} kB`;
  return `${(n / 1024 / 1024).toFixed(1)} MB`;
}
