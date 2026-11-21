import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, cleanup, renderHook } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { AuthContext } from '@/shared/auth/authContext';
import * as api from '../api/courierDashboardApi';
import { dashboardFixture } from '../pages/__tests__/dashboardFixtures';
import { useCourierTodayDashboard } from './useCourierTodayDashboard';

afterEach(() => { cleanup(); vi.useRealTimers(); vi.restoreAllMocks(); });
function setup() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: true } } });
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>
    <AuthContext.Provider value={{ user: { id: 'synthetic-user', accessToken: 'synthetic-token' } } as never}>{children}</AuthContext.Provider>
  </QueryClientProvider>;
  return { ...renderHook(() => useCourierTodayDashboard(1), { wrapper }), client };
}
describe('Courier dashboard query lifecycle', () => {
  it('polls reads every 15 seconds and stops polling and retries after access is denied', async () => {
    vi.useFakeTimers();
    const read = vi.spyOn(api, 'getCourierTodayDashboard').mockResolvedValue({ dashboard: dashboardFixture(), partial: false });
    const view = setup();
    await act(async () => { await vi.advanceTimersByTimeAsync(0); });
    expect(read).toHaveBeenCalledTimes(1);
    await act(async () => { await vi.advanceTimersByTimeAsync(15_000); });
    expect(read).toHaveBeenCalledTimes(2);
    read.mockRejectedValue(new api.CourierDashboardError('forbidden'));
    await act(async () => { await vi.advanceTimersByTimeAsync(15_000); });
    expect(read).toHaveBeenCalledTimes(3);
    await act(async () => { await vi.advanceTimersByTimeAsync(45_000); });
    expect(read).toHaveBeenCalledTimes(3); expect(view.result.current.isError).toBe(true);
    view.unmount(); view.client.clear();
  });
  it('bounds retries for a transient dependency failure', async () => {
    vi.useFakeTimers();
    const read = vi.spyOn(api, 'getCourierTodayDashboard').mockRejectedValue(new api.CourierDashboardError('dependency'));
    const view = setup();
    await act(async () => { await vi.advanceTimersByTimeAsync(1200); });
    expect(read).toHaveBeenCalledTimes(3); expect(view.result.current.isError).toBe(true);
    view.unmount(); view.client.clear();
  });
});
