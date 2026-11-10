import { beforeEach, describe, expect, it, vi } from 'vitest';
import { apiClient } from '@/shared/api/apiClient';
import { getCourierProfile, provisionCourierProfile } from './courierApi';

vi.mock('@/shared/api/apiClient', () => ({
  apiClient: { get: vi.fn(), post: vi.fn() },
  withBearerToken: vi.fn(() => ({})),
}));

describe('courier API endpoint compatibility fallback', () => {
  beforeEach(() => vi.clearAllMocks());

  it('uses the alternate profile route only when the primary route is missing', async () => {
    vi.mocked(apiClient.get)
      .mockRejectedValueOnce(Object.assign(new Error('missing'), { isAxiosError: true, response: { status: 404 } }))
      .mockResolvedValueOnce({ data: { id: 'courier-1' } } as never);
    await expect(getCourierProfile('token')).resolves.toEqual({ id: 'courier-1' });
    expect(apiClient.get).toHaveBeenCalledTimes(2);
  });

  it('does not replay the profile request after unauthorized or network failures', async () => {
    const unauthorized = Object.assign(new Error('unauthorized'), { isAxiosError: true, response: { status: 401 } });
    vi.mocked(apiClient.get).mockRejectedValueOnce(unauthorized);
    await expect(getCourierProfile('token')).rejects.toBe(unauthorized);
    expect(apiClient.get).toHaveBeenCalledTimes(1);
  });

  it('does not replay profile provisioning after a non-404 failure', async () => {
    const unavailable = Object.assign(new Error('unavailable'), { isAxiosError: true, response: { status: 503 } });
    vi.mocked(apiClient.post).mockRejectedValueOnce(unavailable);
    await expect(provisionCourierProfile('token')).rejects.toBe(unavailable);
    expect(apiClient.post).toHaveBeenCalledTimes(1);
  });
});
