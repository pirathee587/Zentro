import { describe, expect, it, vi, beforeEach } from 'vitest';
import type { ReactNode } from 'react';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthContext } from '@/shared/auth/authContext';
import * as api from '../../api/deliveryApi';
import { auditAccessibility } from '@/shared/testing/accessibility';
import { CourierRoutePage } from '../CourierRoutePage';
import { CourierHistoryPage } from '../CourierHistoryPage';
import { CourierEarningsPage } from '../CourierEarningsPage';
const id = '11111111-1111-4111-8111-111111111111';
const row: api.DeliverySummary = {
  parcelId: id,
  courierId: '22222222-2222-4222-8222-222222222222',
  assignedAt: '2026-11-17T08:00:00Z',
  proofId: null,
  completionState: null,
};
const login = vi.fn();
const account = {
  id: 'owner',
  accessToken: 'synthetic-token',
  name: 'Fixture',
  roles: ['COURIER'],
};
function mount(page: ReactNode, user: unknown = account) {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  const tree = (value: unknown) => (
    <QueryClientProvider client={client}>
      <AuthContext.Provider
        value={
          {
            user: value,
            login,
            logout: vi.fn(),
            loading: false,
            setUser: vi.fn(),
            register: vi.fn(),
          } as never
        }
      >
        <MemoryRouter>{page}</MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>
  );
  const result = render(tree(user));
  return { ...result, changeAccount: (value: unknown) => result.rerender(tree(value)) };
}
beforeEach(() => {
  vi.restoreAllMocks();
  login.mockClear();
});
describe('Courier route REST integration', () => {
  it('loads authenticated assignments and canonical links without fake destinations', async () => {
    const read = vi.spyOn(api, 'listDeliveries').mockResolvedValue([row]);
    mount(<CourierRoutePage />);
    expect(await screen.findByRole('link', { name: 'View delivery' })).toHaveAttribute(
      'href',
      `/courier/deliveries/${id}`,
    );
    expect(read).toHaveBeenCalledWith('synthetic-token', expect.any(AbortSignal));
    expect(screen.getByText(/1 assignments/)).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: /maps/i })).not.toBeInTheDocument();
  });
  it('shows loading then a real empty state', async () => {
    let resolve!: (rows: api.DeliverySummary[]) => void;
    vi.spyOn(api, 'listDeliveries').mockReturnValue(
      new Promise((r) => {
        resolve = r;
      }),
    );
    mount(<CourierRoutePage />);
    expect(screen.getByRole('status')).toHaveTextContent('Loading');
    resolve([]);
    expect(await screen.findByText('No assigned deliveries')).toBeInTheDocument();
  });
  it('shows safe errors and a working refresh', async () => {
    const read = vi.spyOn(api, 'listDeliveries').mockRejectedValue(new Error('private'));
    mount(<CourierRoutePage />);
    expect(await screen.findByRole('alert')).not.toHaveTextContent('private');
    read.mockResolvedValue([]);
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('No assigned deliveries')).toBeInTheDocument();
  });
  it('does not request deliveries without a session', () => {
    const read = vi.spyOn(api, 'listDeliveries');
    mount(<CourierRoutePage />, null);
    expect(screen.getByText('Sign in to view your route.')).toBeInTheDocument();
    expect(read).not.toHaveBeenCalled();
  });
  it('does not reuse another courier cache', async () => {
    vi.spyOn(api, 'listDeliveries').mockResolvedValueOnce([row]).mockResolvedValue([]);
    const view = mount(<CourierRoutePage />);
    await screen.findByRole('link', { name: 'View delivery' });
    view.changeAccount({ ...account, id: 'other', accessToken: 'other-token' });
    expect(await screen.findByText('No assigned deliveries')).toBeInTheDocument();
    expect(screen.queryByText(id, { exact: false })).not.toBeInTheDocument();
  });
});

describe('Courier history available proof records', () => {
  const proof = {
    ...row,
    proofId: '33333333-3333-4333-8333-333333333333',
    completionState: 'COMPLETED' as const,
  };
  it('uses real proof state, filters and canonical navigation without fake failure statuses', async () => {
    vi.spyOn(api, 'listDeliveries').mockResolvedValue([
      proof,
      {
        ...row,
        parcelId: '44444444-4444-4444-8444-444444444444',
        proofId: '55555555-5555-4555-8555-555555555555',
        completionState: 'REJECTED',
      },
    ]);
    mount(<CourierHistoryPage />);
    expect(await screen.findByText('2 matching proof records')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Proof state'), { target: { value: 'COMPLETED' } });
    expect(screen.getByText('1 matching proof records')).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'View delivery and proof' })).toHaveAttribute(
      'href',
      `/courier/deliveries/${id}`,
    );
    fireEvent.change(screen.getByLabelText('Search parcel ID'), { target: { value: 'not-found' } });
    expect(screen.getByText('No records match your filters')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }));
    expect(screen.getByText('2 matching proof records')).toBeInTheDocument();
  });
  it('filters actual assignment dates and rejects invalid ranges', async () => {
    vi.spyOn(api, 'listDeliveries').mockResolvedValue([proof]);
    mount(<CourierHistoryPage />);
    await screen.findByText('1 matching proof records');
    fireEvent.change(screen.getByLabelText('Assigned from'), { target: { value: '2026-11-18' } });
    expect(screen.getByText('No records match your filters')).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText('Assigned through'), {
      target: { value: '2026-11-17' },
    });
    expect(screen.getByRole('alert')).toHaveTextContent('start date');
  });
  it('paginates the loaded records and resets after filtering', async () => {
    const rows = Array.from({ length: 11 }, (_, i) => ({
      ...proof,
      parcelId: `11111111-1111-4111-8111-${String(i + 1).padStart(12, '0')}`,
    }));
    vi.spyOn(api, 'listDeliveries').mockResolvedValue(rows);
    mount(<CourierHistoryPage />);
    await screen.findByText('11 matching proof records');
    expect(screen.getAllByRole('listitem')).toHaveLength(10);
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getAllByRole('listitem')).toHaveLength(1);
    expect(screen.getByText('Page 2 of 2')).toBeInTheDocument();
  });
  it('does not turn assignments without proof into historical completions', async () => {
    vi.spyOn(api, 'listDeliveries').mockResolvedValue([row]);
    mount(<CourierHistoryPage />);
    expect(await screen.findByText('No proof records available')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'View delivery and proof' })).not.toBeInTheDocument();
  });
  it('shows errors without leaking prior records', async () => {
    vi.spyOn(api, 'listDeliveries').mockRejectedValue(new Error('private'));
    mount(<CourierHistoryPage />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Delivery records unavailable');
    expect(screen.queryByRole('listitem')).not.toBeInTheDocument();
  });
});

describe('Courier earnings without a financial API', () => {
  it('shows an honest unavailable state without invented amounts or charts', () => {
    const deliveries = vi.spyOn(api, 'listDeliveries');
    mount(<CourierEarningsPage />);
    expect(screen.getByRole('heading', { level: 1, name: 'Earnings' })).toBeInTheDocument();
    expect(screen.getByText('Earnings unavailable')).toBeInTheDocument();
    expect(screen.queryByText('80%')).not.toBeInTheDocument();
    expect(screen.queryByRole('combobox')).not.toBeInTheDocument();
    expect(deliveries).not.toHaveBeenCalled();
  });
  it('provides working navigation to existing courier pages', () => {
    mount(<CourierEarningsPage />);
    expect(screen.getByRole('link', { name: /Today dashboard/ })).toHaveAttribute(
      'href',
      '/courier',
    );
    expect(screen.getByRole('link', { name: 'View delivery records' })).toHaveAttribute(
      'href',
      '/courier/history',
    );
  });
});

describe('Courier secondary page security and accessibility', () => {
  it('clears visible route records when refreshed ownership is denied', async () => {
    const read = vi.spyOn(api, 'listDeliveries').mockResolvedValue([row]);
    mount(<CourierRoutePage />);
    await screen.findByRole('link', { name: 'View delivery' });
    read.mockRejectedValue({ isAxiosError: true, response: { status: 403 } });
    fireEvent.click(screen.getByRole('button', { name: /Refresh/ }));
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'View delivery' })).not.toBeInTheDocument();
  });
  it('offers sign-in instead of retrying an expired session', async () => {
    const read = vi
      .spyOn(api, 'listDeliveries')
      .mockRejectedValue({ isAxiosError: true, response: { status: 401 } });
    mount(<CourierRoutePage />);
    fireEvent.click(await screen.findByRole('button', { name: 'Sign in again' }));
    expect(login).toHaveBeenCalledWith('/', undefined, 'staff');
    expect(read).toHaveBeenCalledTimes(1);
  });
  it('rejects malformed assignment data rather than inventing a delivery', async () => {
    vi.spyOn(api, 'listDeliveries').mockResolvedValue([{ ...row, assignedAt: 'invalid' }]);
    mount(<CourierRoutePage />);
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.queryByRole('link', { name: 'View delivery' })).not.toBeInTheDocument();
  });
  for (const [name, Page] of [
    ['Route', CourierRoutePage],
    ['History', CourierHistoryPage],
    ['Earnings', CourierEarningsPage],
  ] as const) {
    it(`${name} has labeled controls and no detected DOM accessibility violations`, async () => {
      vi.spyOn(api, 'listDeliveries').mockResolvedValue([]);
      const view = mount(<Page />);
      if (name === 'Route') await screen.findByText('No assigned deliveries');
      if (name === 'History') await screen.findByText('No proof records available');
      const report = await auditAccessibility(view.container);
      expect(report.violations).toEqual([]);
    });
  }
});
