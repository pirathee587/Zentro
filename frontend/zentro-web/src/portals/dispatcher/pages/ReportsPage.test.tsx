import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { getDailyDeliveries, getDeliverySummary } from '../api/reportApi';
import { ReportsPage } from './ReportsPage';

vi.mock('../api/reportApi');
vi.mock('@/shared/auth/useAuth', () => ({
  useAuth: () => ({ user: { id: 'dispatcher-test', accessToken: 'test-token' } }),
}));

describe('ReportsPage summary API contract', () => {
  it.each([
    [0.8, '80%'],
    [0, '0%'],
    [null, '—'],
  ])('renders successRate %s as %s', async (successRate, expected) => {
    vi.mocked(getDeliverySummary).mockResolvedValue({
      from: '2026-11-01', to: '2026-11-19', timezone: 'Asia/Colombo',
      created: 10, delivered: 8, failed: 2, successRate,
    });
    vi.mocked(getDailyDeliveries).mockResolvedValue({
      from: '2026-11-01', to: '2026-11-19', timezone: 'Asia/Colombo', days: [],
    });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><ReportsPage /></QueryClientProvider>);
    const metric = screen.getByText('Success rate').parentElement!;
    expect(await within(metric).findByText(expected)).toBeVisible();
    client.clear();
  });
});
