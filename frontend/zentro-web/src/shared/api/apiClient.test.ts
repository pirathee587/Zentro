import { describe, expect, it } from 'vitest';
import { friendlyApiError } from './apiClient';

function axiosError(status?: number) {
  return Object.assign(new Error('request failed'), {
    isAxiosError: true,
    ...(status === undefined ? {} : { response: { status } }),
  });
}

describe('friendlyApiError', () => {
  it('maps authentication and permission responses to safe messages', () => {
    expect(friendlyApiError(axiosError(401), 'Fallback')).toMatch(/session has expired/i);
    expect(friendlyApiError(axiosError(403), 'Fallback')).toMatch(/does not have permission/i);
  });

  it('maps network failures and uses the feature fallback for other statuses', () => {
    expect(friendlyApiError(axiosError(), 'Fallback')).toMatch(/could not reach Zentro/i);
    expect(friendlyApiError(axiosError(500), 'Try again later')).toBe('Try again later');
    expect(friendlyApiError(new Error('unexpected'), 'Try again later')).toBe('Try again later');
  });
});
