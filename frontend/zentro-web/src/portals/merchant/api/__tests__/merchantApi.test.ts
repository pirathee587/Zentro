import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '@/shared/api/apiClient';
import {
  createMerchantApplication,
  getMerchantApiKey,
  getMerchantApplication,
  issueMerchantApiKey,
  revokeMerchantApiKey,
} from '../merchantApi';

vi.mock('@/shared/api/apiClient', () => ({
  apiClient: {
    get: vi.fn(),
    post: vi.fn(),
    delete: vi.fn(),
  },
}));

const auth = { accessToken: 'merchant-token' };

describe('merchant API', () => {
  beforeEach(() => vi.clearAllMocks());

  it('loads the current merchant application with the bearer token', async () => {
    const application = { businessName: 'Good Things', status: 'PENDING' as const };
    vi.mocked(apiClient.get).mockResolvedValueOnce({ data: application } as never);

    await expect(getMerchantApplication(auth)).resolves.toEqual(application);
    expect(apiClient.get).toHaveBeenCalledWith('/api/v1/merchants/me', {
      headers: { Authorization: 'Bearer merchant-token' },
    });
  });

  it('retries a request once with a refreshed token after a 401', async () => {
    const refreshAccessToken = vi.fn(async (force = false) => force ? 'fresh-token' : 'expired-token');
    const application = { businessName: 'Good Things', status: 'APPROVED' as const };
    const unauthorized = Object.assign(new Error('Unauthorized'), {
      isAxiosError: true,
      response: { status: 401 },
    });
    vi.mocked(apiClient.get)
      .mockRejectedValueOnce(unauthorized)
      .mockResolvedValueOnce({ data: application } as never);

    await expect(getMerchantApplication({ refreshAccessToken })).resolves.toEqual(application);
    expect(refreshAccessToken).toHaveBeenNthCalledWith(1, false);
    expect(refreshAccessToken).toHaveBeenNthCalledWith(2, true);
    expect(apiClient.get).toHaveBeenLastCalledWith('/api/v1/merchants/me', {
      headers: { Authorization: 'Bearer fresh-token' },
    });
  });

  it('does not retry a forbidden request', async () => {
    const refreshAccessToken = vi.fn(async () => 'fresh-token');
    const forbidden = Object.assign(new Error('Forbidden'), {
      isAxiosError: true,
      response: { status: 403 },
    });
    vi.mocked(apiClient.get).mockRejectedValueOnce(forbidden);

    await expect(getMerchantApplication({ refreshAccessToken })).rejects.toBe(forbidden);
    expect(refreshAccessToken).toHaveBeenCalledTimes(1);
    expect(apiClient.get).toHaveBeenCalledTimes(1);
  });

  it('submits merchant registration using the existing backend contract', async () => {
    const request = {
      businessName: 'Good Things',
      businessRegNo: 'BR-100',
      contactPhone: '+94770000000',
    };
    const application = { businessName: request.businessName, status: 'PENDING' as const };
    vi.mocked(apiClient.post).mockResolvedValueOnce({ data: application } as never);

    await expect(createMerchantApplication(auth, request)).resolves.toEqual(application);
    expect(apiClient.post).toHaveBeenCalledWith('/api/v1/merchants', request, {
      headers: { Authorization: 'Bearer merchant-token' },
    });
  });

  it('uses the existing API-key read, issue, and revoke endpoints', async () => {
    const metadata = { id: 'key-1', prefix: 'zn_live_abc', active: true };
    vi.mocked(apiClient.get).mockResolvedValueOnce({ data: metadata } as never);
    vi.mocked(apiClient.post).mockResolvedValueOnce({ data: { secret: 'one-time-secret' } } as never);
    vi.mocked(apiClient.delete).mockResolvedValueOnce({ data: null } as never);

    await expect(getMerchantApiKey(auth)).resolves.toEqual(metadata);
    await expect(issueMerchantApiKey(auth)).resolves.toEqual({ secret: 'one-time-secret' });
    await expect(revokeMerchantApiKey(auth)).resolves.toBeNull();

    expect(apiClient.get).toHaveBeenCalledWith('/api/v1/merchants/api-key', expect.any(Object));
    expect(apiClient.post).toHaveBeenCalledWith('/api/v1/merchants/api-key', undefined, expect.any(Object));
    expect(apiClient.delete).toHaveBeenCalledWith('/api/v1/merchants/api-key', expect.any(Object));
  });
});
