import { describe, expect, it } from 'vitest';
import { AxiosError } from 'axios';
import { ApiClientError } from './apiClient';
import { queryClient, shouldRetryQuery } from './queryClient';

function apiFailure(status?: number, code?: string) {
  const axiosError = new AxiosError('failed', code, undefined, undefined, status === undefined ? undefined : ({
    data: {}, status, statusText: '', headers: {}, config: {},
  } as never));
  return new ApiClientError(axiosError);
}

describe('query defaults', () => {
  it('retries network errors and 5xx responses within the bound', () => {
    expect(shouldRetryQuery(0, apiFailure(undefined, 'ERR_NETWORK'))).toBe(true);
    for (const status of [500, 502, 503, 504]) expect(shouldRetryQuery(1, apiFailure(status))).toBe(true);
    expect(shouldRetryQuery(2, apiFailure(503))).toBe(false);
  });

  it('does not retry client errors or non-network unknown failures', () => {
    for (const status of [400, 401, 403, 404, 409, 422]) {
      expect(shouldRetryQuery(0, apiFailure(status))).toBe(false);
    }
    expect(shouldRetryQuery(0, new Error('unclassified'))).toBe(false);
  });

  it('does not retry mutations automatically', () => {
    expect(queryClient.getDefaultOptions().mutations?.retry).toBe(false);
  });
});
