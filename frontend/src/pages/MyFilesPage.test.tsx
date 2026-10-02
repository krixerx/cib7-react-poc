// @vitest-environment happy-dom
//
// Renders "My files" against canned documents from two cases: issued
// documents are the default view, the uploads filter and the search narrow
// the list, and every row links back to its own case.

import { act } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import '../i18n';
import type { CaseDocumentEntry } from '../api/documentsApi';

const docs: CaseDocumentEntry[] = [
  {
    id: 'd-cert',
    processInstanceId: 'pi-vehicle',
    category: 'generated-certificate',
    filename: 'registration-certificate.pdf',
    contentType: 'application/pdf',
    createdAt: '2026-10-02T09:00:00Z',
  },
  {
    id: 'd-permit',
    processInstanceId: 'pi-permit',
    category: 'generated-certificate',
    filename: 'learning-license.pdf',
    contentType: 'application/pdf',
    createdAt: '2026-10-01T09:00:00Z',
  },
  {
    id: 'd-id',
    processInstanceId: 'pi-vehicle',
    category: 'applicant-id-document',
    filename: 'passport.png',
    contentType: 'image/png',
    createdAt: '2026-09-30T09:00:00Z',
  },
];

vi.mock('../api/documentsApi', async (importOriginal) => ({
  ...(await importOriginal<typeof import('../api/documentsApi')>()),
  listMyDocuments: vi.fn(async () => docs),
  getDownloadUrl: vi.fn(async () => ({ url: 'http://s3/signed', expiresIn: 60 })),
}));

vi.mock('../api/camundaClient', () => ({
  listProcessDefinitions: vi.fn(async () => [
    { id: 'def-v', key: 'vehicleRegistration', name: 'Vehicle Registration', version: 1 },
    {
      id: 'def-p',
      key: 'transportLearningPermit',
      name: 'Transport Driving Learner Permit',
      version: 1,
    },
  ]),
  listHistoricProcessInstancesByStarter: vi.fn(async () => [
    { id: 'pi-vehicle', processDefinitionId: 'def-v', processDefinitionKey: 'vehicleRegistration' },
    {
      id: 'pi-permit',
      processDefinitionId: 'def-p',
      processDefinitionKey: 'transportLearningPermit',
    },
  ]),
}));

vi.mock('../auth/AuthProvider', () => ({ useAuth: () => ({ username: 'bart' }) }));

const { default: MyFilesPage } = await import('./MyFilesPage');

describe('MyFilesPage', () => {
  let container: HTMLDivElement;
  let root: Root;

  beforeEach(async () => {
    (globalThis as { IS_REACT_ACT_ENVIRONMENT?: boolean }).IS_REACT_ACT_ENVIRONMENT = true;
    container = document.createElement('div');
    document.body.appendChild(container);
    root = createRoot(container);
    await act(async () => {
      root.render(
        <MemoryRouter>
          <MyFilesPage />
        </MemoryRouter>,
      );
    });
  });

  afterEach(() => {
    act(() => root.unmount());
    container.remove();
  });

  const names = () =>
    [...container.querySelectorAll('.document-row-title')].map((n) => n.textContent);

  function click(label: string) {
    const chip = [...container.querySelectorAll<HTMLButtonElement>('.mf-filters .chip')].find((b) =>
      b.textContent?.startsWith(label),
    );
    act(() => chip!.click());
  }

  it('shows issued documents of every case by default, each linked to its case', () => {
    expect(names()).toEqual(['registration-certificate.pdf', 'learning-license.pdf']);
    const links = [...container.querySelectorAll('.mf-case-link')].map((a) =>
      a.getAttribute('href'),
    );
    expect(links).toEqual(['/processes/pi-vehicle', '/processes/pi-permit']);
    expect(container.textContent).toContain('Vehicle Registration');
    expect(container.textContent).toContain('3 files');
    expect(container.textContent).toContain('from 2 cases');
  });

  it('switches to uploads and back to all files', () => {
    click('Uploaded by me');
    expect(names()).toEqual(['passport.png']);
    click('All files');
    expect(names()).toHaveLength(3);
  });

  it('narrows the list by search text', () => {
    click('All files');
    const input = container.querySelector<HTMLInputElement>('.mf-search input')!;
    act(() => {
      const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!;
      setter.call(input, 'permit');
      input.dispatchEvent(new Event('input', { bubbles: true }));
    });
    expect(names()).toEqual(['learning-license.pdf']);
  });
});
