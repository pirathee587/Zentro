import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthContext } from '@/shared/auth/authContext';
import { apiClient } from '@/shared/api/apiClient';
import { DispatchControlPage } from './DispatchControlPage';

const parcelId = '11111111-1111-4111-8111-111111111111';
const courierId = '22222222-2222-4222-8222-222222222222';
const parcel = {
  parcelId,
  trackingReference: 'LIVE-PARCEL',
  pickupSummary: 'Colombo',
  dropoffSummary: 'Kandy',
  createdAt: '2026-11-16T10:00:00Z',
};
const courier = {
  courierId,
  userId: parcelId,
  name: 'Live Courier',
  active: true,
  accountStatus: 'ACTIVE',
  assignmentCount: 12,
  availability: 'AVAILABLE',
};
let incomplete = false;
function mount() {
  const client = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={client}>
      <AuthContext.Provider
        value={{ user: { id: 'dispatcher', accessToken: 'test' }, loading: false } as never}
      >
        <MemoryRouter>
          <DispatchControlPage />
        </MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>,
  );
}
beforeEach(() => {
  vi.restoreAllMocks();
  incomplete = false;
  vi.spyOn(apiClient, 'get').mockImplementation(async (url) => {
    if (url === '/api/v1/dispatch/queue')
      return {
        data: {
          items: [parcel],
          total: incomplete ? null : 1,
          matchingTotal: incomplete ? null : 1,
          complete: !incomplete,
        },
      } as never;
    if (url === '/api/v1/dispatch/workload')
      return { data: { items: [courier], total: 1, overloadConfigured: false } } as never;
    if (url === '/api/v1/dispatch/eligible-couriers')
      return {
        data: [
          {
            id: courierId,
            userId: parcelId,
            name: 'Live Courier',
            active: true,
            accountStatus: 'ACTIVE',
          },
        ],
      } as never;
    return { data: [] } as never;
  });
});
describe('Dispatcher dashboard', () => {
  it('uses service totals and honest labels without invented capacity or activity', async () => {
    mount();
    expect(await screen.findByText('LIVE-PARCEL')).toBeInTheDocument();
    expect(
      within(screen.getByRole('article', { name: 'Recorded assignments' })).getByText('12'),
    ).toBeInTheDocument();
    expect(
      within(screen.getByRole('article', { name: 'Available couriers' })).getByText('1'),
    ).toBeInTheDocument();
    expect(screen.getByRole('table')).toHaveTextContent('Colombo');
    expect(screen.getByRole('table')).toHaveTextContent('Unavailable');
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
    expect(screen.queryByText(/notification attempts failed/)).not.toBeInTheDocument();
  });
  it('keeps incomplete totals unavailable instead of treating them as zero', async () => {
    incomplete = true;
    mount();
    await screen.findByText('LIVE-PARCEL');
    expect(
      within(screen.getByRole('article', { name: 'Unassigned parcels' })).getByText('Unavailable'),
    ).toBeInTheDocument();
    expect(screen.getByText(/Incomplete source snapshot/)).toBeInTheDocument();
  });
  it('shows loading and error states without sample data', async () => {
    vi.mocked(apiClient.get).mockRejectedValue(new Error('Offline'));
    mount();
    expect(screen.getAllByText('Loading…')).toHaveLength(3);
    expect(await screen.findAllByRole('alert')).toHaveLength(3);
    expect(screen.getAllByRole('button', { name: 'Retry' })).toHaveLength(3);
    expect(screen.queryByText('LIVE-PARCEL')).not.toBeInTheDocument();
  });
  it('uses the existing eligible-courier confirmation and assignment API', async () => {
    vi.spyOn(apiClient, 'post').mockResolvedValue({ data: {} });
    mount();
    fireEvent.click(await screen.findByRole('button', { name: 'Assign LIVE-PARCEL' }));
    await screen.findByRole('option', { name: /Live Courier/ });
    fireEvent.change(screen.getByLabelText('Courier'), { target: { value: courierId } });
    fireEvent.click(screen.getByRole('button', { name: 'Confirm assignment' }));
    expect(await screen.findByText('Delivery assigned successfully.')).toBeInTheDocument();
    expect(apiClient.post).toHaveBeenCalledWith(
      '/api/v1/dispatch/assignments',
      { parcelId, courierId },
      expect.objectContaining({
        headers: expect.objectContaining({ Authorization: 'Bearer test' }),
      }),
    );
  });
  it('renders empty sections and actual zero totals', async () => {
    vi.mocked(apiClient.get).mockImplementation(
      async (url) =>
        ({
          data:
            url === '/api/v1/dispatch/queue'
              ? { items: [], total: 0, matchingTotal: 0, complete: true }
              : url === '/api/v1/dispatch/workload'
                ? { items: [], total: 0, overloadConfigured: false }
                : [],
        }) as never,
    );
    mount();
    expect(await screen.findByText('All clear — no parcels waiting.')).toBeInTheDocument();
    expect(screen.getByText('No couriers to display.')).toBeInTheDocument();
    expect(
      within(screen.getByRole('article', { name: 'Unassigned parcels' })).getByText('0'),
    ).toBeInTheDocument();
  });
});
