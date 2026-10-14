import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { listServices } from './servicesApi';

const fetchMock = vi.fn();

beforeEach(() => {
  fetchMock.mockReset();
  vi.stubGlobal('fetch', fetchMock);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe('listServices', () => {
  it('reads the public list from the backend without a token', async () => {
    const services = [{ id: 'vr:1:a', key: 'vr', name: 'Vehicle registration', version: 1 }];
    fetchMock.mockResolvedValue({ ok: true, status: 200, json: async () => services });

    await expect(listServices()).resolves.toEqual(services);

    const [url, init] = fetchMock.mock.calls[0] as [string, RequestInit];
    expect(url).toBe('/api/public/services');
    expect(init.headers).toBeUndefined();
  });

  it('throws on an error status', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 502, json: async () => ({}) });

    await expect(listServices()).rejects.toThrow('services unavailable (502)');
  });
});
