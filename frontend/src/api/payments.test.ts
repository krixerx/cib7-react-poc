import { afterEach, describe, expect, it, vi } from 'vitest';
import * as payments from './paymentsApi';
import { safeReturnPath } from './mockBankApi';

describe('paymentsApi', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('reads the bill and starts a checkout with the opaque token, nothing else', async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ sessionId: 's1', redirectUrl: '/mock-bank/s1' }), {
        status: 200,
      }),
    );
    vi.stubGlobal('fetch', fetchMock);

    await payments.checkout('abc.def');

    expect(fetchMock).toHaveBeenCalledWith(
      '/api/public/payments/abc.def/checkout',
      expect.objectContaining({ method: 'POST' }),
    );
  });

  it('has no browser-side way to mark a fee as paid', () => {
    // The fee is settled only by the provider's signed callback (docs/security.md rule 4).
    expect(Object.keys(payments)).not.toContain('confirm');
  });
});

describe('safeReturnPath', () => {
  it('accepts only pay pages of this SPA', () => {
    expect(safeReturnPath('/pay/abc_DEF-1.sig')).toBe('/pay/abc_DEF-1.sig');
    expect(safeReturnPath('https://evil.example/pay/x')).toBeNull();
    expect(safeReturnPath('//evil.example/pay/x')).toBeNull();
    expect(safeReturnPath('/pay/x/../../admin')).toBeNull();
    expect(safeReturnPath('/tasks/1')).toBeNull();
    expect(safeReturnPath(undefined)).toBeNull();
  });
});
