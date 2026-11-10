import { QueryClient } from '@tanstack/react-query';
import { ApiClientError } from './apiClient';

export const QUERY_RETRY_LIMIT = 2;

export function shouldRetryQuery(failureCount: number, error: unknown): boolean {
  if (failureCount >= QUERY_RETRY_LIMIT || !(error instanceof ApiClientError)) return false;
  return error.category === 'network' || (error.category === 'http' && error.status !== undefined && error.status >= 500);
}

export const queryClient = new QueryClient({
  defaultOptions: {
    queries: {
      retry: shouldRetryQuery,
      retryDelay: (attempt) => Math.min(300 * 2 ** attempt, 1_000),
      staleTime: 30_000,
      gcTime: 5 * 60_000,
      refetchOnWindowFocus: false,
    },
    mutations: { retry: false },
  },
});
