/**
 * Typed client for the `/api/documents` backend.
 *
 * Two distinct mechanics in here:
 *
 *  1. Same-origin JSON endpoints — Bearer-authed (see camundaClient.ts for the
 *     pattern). Used to mint presigned URLs, register attachments after a
 *     successful PUT, list documents on a process instance, and mint
 *     short-lived GET URLs for download.
 *  2. Direct PUT to RustFS via the presigned URL — uses `XMLHttpRequest`
 *     instead of `fetch` so the dropzone can render a real progress bar.
 *     The URL is signed by the backend; the browser must send exactly the
 *     headers RustFS expects or the signature check fails.
 */

import { keycloak, ensureFreshToken } from '../auth/keycloak';

const BASE = '/api/documents';

export interface UploadUrlInput {
  filename: string;
  contentType: string;
  size: number;
  scope: 'pending' | 'process';
  /** Required when scope is 'process'. */
  scopeId?: string;
}

export interface UploadUrlResponse {
  key: string;
  url: string;
  headers: Record<string, string>;
  /** Seconds until the URL stops working. */
  expiresIn: number;
}

export interface AttachmentRegisterInput {
  key: string;
  filename: string;
  contentType: string;
  category: DocumentCategory;
}

export interface AttachmentResponse {
  attachmentId: string;
  key: string;
}

export type DocumentCategory =
  | 'applicant-id-document'
  | 'generated-approval-pdf'
  | 'generated-certificate';

export interface DocumentEntry {
  id: string;
  category: DocumentCategory;
  filename: string;
  contentType: string;
  createdAt: string | null;
  uploaderUserId: string | null;
  key: string;
}

export interface DownloadUrlResponse {
  url: string;
  expiresIn: number;
}

export interface ErrorBody {
  code: string;
  message: string;
}

export class DocumentsApiError extends Error {
  readonly httpStatus: number;
  readonly code: string;
  constructor(httpStatus: number, body: ErrorBody) {
    super(body.message);
    this.httpStatus = httpStatus;
    this.code = body.code;
  }
}

async function request<T>(path: string, init?: RequestInit): Promise<T> {
  const token = keycloak.authenticated ? await ensureFreshToken() : null;
  const res = await fetch(BASE + path, {
    cache: 'no-store',
    ...init,
    headers: {
      'Content-Type': 'application/json',
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(init?.headers ?? {}),
    },
  });
  if (!res.ok) {
    let body: ErrorBody;
    try {
      body = (await res.json()) as ErrorBody;
    } catch {
      body = { code: 'http_' + res.status, message: res.statusText || 'Request failed' };
    }
    throw new DocumentsApiError(res.status, body);
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export function requestUploadUrl(input: UploadUrlInput): Promise<UploadUrlResponse> {
  return request('/upload-url', { method: 'POST', body: JSON.stringify(input) });
}

export function confirmAttachment(
  processInstanceId: string,
  body: AttachmentRegisterInput,
): Promise<AttachmentResponse> {
  return request(`/${encodeURIComponent(processInstanceId)}/attachments`, {
    method: 'POST',
    body: JSON.stringify(body),
  });
}

export function listAttachments(processInstanceId: string): Promise<DocumentEntry[]> {
  return request(`/${encodeURIComponent(processInstanceId)}`);
}

export function getDownloadUrl(attachmentId: string): Promise<DownloadUrlResponse> {
  return request(`/attachments/${encodeURIComponent(attachmentId)}/download-url`);
}

/**
 * PUTs `file` to a presigned URL with the headers the backend baked into the
 * signature. Yields progress via `onProgress(fraction)` between 0 and 1.
 * Rejects with a `DocumentsApiError` on non-2xx, mirroring `request<T>` so
 * callers handle both failure paths the same way.
 */
export function uploadToPresigned(
  url: string,
  headers: Record<string, string>,
  file: File,
  onProgress?: (fraction: number) => void,
): Promise<void> {
  return new Promise((resolve, reject) => {
    const xhr = new XMLHttpRequest();
    xhr.open('PUT', url, true);
    for (const [k, v] of Object.entries(headers)) {
      xhr.setRequestHeader(k, v);
    }
    if (onProgress) {
      xhr.upload.onprogress = (ev) => {
        if (ev.lengthComputable) onProgress(ev.loaded / ev.total);
      };
    }
    xhr.onload = () => {
      if (xhr.status >= 200 && xhr.status < 300) {
        if (onProgress) onProgress(1);
        resolve();
      } else {
        reject(
          new DocumentsApiError(xhr.status, {
            code: 'upload_failed',
            message: `RustFS PUT failed (${xhr.status}): ${xhr.responseText || xhr.statusText}`,
          }),
        );
      }
    };
    xhr.onerror = () => {
      reject(
        new DocumentsApiError(0, {
          code: 'network_error',
          message: 'Upload to RustFS failed at the network layer.',
        }),
      );
    };
    xhr.send(file);
  });
}

/**
 * Convenience: maps a category code to a UI-friendly label. Kept in this
 * module so the SPA never has to repeat the string literals.
 */
export function categoryLabel(category: DocumentCategory): string {
  switch (category) {
    case 'applicant-id-document':
      return 'ID document';
    case 'generated-approval-pdf':
      return 'Approval PDF';
    case 'generated-certificate':
      return 'Certificate of approval';
  }
}
