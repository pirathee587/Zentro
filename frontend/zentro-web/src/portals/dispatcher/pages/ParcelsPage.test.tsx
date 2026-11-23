import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { apiClient } from '@/shared/api/apiClient';
import { AuthContext } from '@/shared/auth/authContext';
import { ParcelsPage } from './ParcelsPage';
import { deriveParcelStatus } from '../api/parcelsApi';

const QUEUED = '11111111-1111-4111-8111-111111111111';
const ASSIGNED = '22222222-2222-4222-8222-222222222222';
const DELIVERED = '33333333-3333-4333-8333-333333333333';
const COURIER = '44444444-4444-4444-8444-444444444444';

const queue = {
  items: [{ parcelId: QUEUED, trackingReference: 'ZT-QUEUED0001', pickupSummary: null, dropoffSummary: null, createdAt: '2026-11-20T09:00:00Z' }],
  total: 1, matchingTotal: 1, complete: true,
};
const assignment = (parcelId: string, assignmentStatus: string) => ({
  parcelId, courierId: COURIER, courierUserId: COURIER, assignedBy: COURIER, assignedAt: '2026-11-20T10:00:00Z',
  assignmentStatus, respondedAt: null, rejectionReason: null, courierName: 'Zentro Courier 1', courierPhone: '0700000000',
  courierVehicle: '', courierZone: '',
});
const detail = (id: string, trackingRef: string, status: string, from: string, to: string, createdAt: string) => ({
  id, trackingRef, status, fee: 1630, currency: 'LKR', estimatedDays: 2, createdAt,
  sender: { name: 'Sender', phone: '0700000001', address: from, city: null },
  recipient: { name: 'Recipient', phone: '0700000002', address: to, city: null },
  parcel: { weightKg: 10, category: 'Medium Box', size: 'MEDIUM', description: null },
});

let responses: Record<string, unknown>;
let failures: Set<string>;

function mount() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}>
    <AuthContext.Provider value={{ user: { id: 'dispatcher', accessToken: 'test' }, loading: false } as never}>
      <MemoryRouter><ParcelsPage /></MemoryRouter>
    </AuthContext.Provider>
  </QueryClientProvider>);
}

async function rows() {
  const table = await screen.findByRole('table');
  await within(table).findByText('ZT-ASSIGNED01');
  return within(table).getAllByRole('row').slice(1);
}

describe('Dispatcher Parcels page (mocked HTTP, real React Query)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    failures = new Set();
    responses = {
      '/api/v1/dispatch/queue': queue,
      '/api/v1/dispatch/assignments': [assignment(ASSIGNED, 'PENDING_ACCEPTANCE'), assignment(DELIVERED, 'ACCEPTED')],
      [`/api/v1/parcels/${QUEUED}`]: detail(QUEUED, 'ZT-QUEUED0001', 'CREATED', 'Jaffna', 'Kandy', '2026-11-20T09:00:00Z'),
      [`/api/v1/parcels/${ASSIGNED}`]: detail(ASSIGNED, 'ZT-ASSIGNED01', 'CREATED', 'Kodikamam', 'Colombo', '2026-11-20T08:00:00Z'),
      [`/api/v1/parcels/${DELIVERED}`]: detail(DELIVERED, 'ZT-DELIVERED1', 'DELIVERED', 'Galle', 'Matara', '2026-11-19T08:00:00Z'),
    };
    vi.spyOn(apiClient, 'get').mockImplementation(async (url: string) => {
      if (failures.has(url)) throw new Error('boom');
      if (!(url in responses)) throw new Error(`unexpected ${url}`);
      return { data: responses[url] } as never;
    });
  });

  it('shows loading instead of an empty total while requests are pending', () => {
    vi.mocked(apiClient.get).mockReturnValue(new Promise(() => {}));
    mount();
    expect(screen.getAllByText('Loading parcels…').length).toBeGreaterThan(0);
    expect(screen.queryByText(/0 parcels/)).not.toBeInTheDocument();
  });

  it('lists unassigned and assigned backend parcels with real status and courier, without a preview banner', async () => {
    mount();
    const [first, second, third] = await rows();
    expect(first).toHaveTextContent('ZT-QUEUED0001');
    expect(first).toHaveTextContent('Jaffna to Kandy');
    expect(first).toHaveTextContent('Unassigned');
    expect(second).toHaveTextContent('ZT-ASSIGNED01');
    expect(second).toHaveTextContent('Assigned');
    expect(second).toHaveTextContent('Zentro Courier 1');
    expect(third).toHaveTextContent('Delivered');
    expect(screen.getByText('3 parcels · 1 unassigned')).toBeInTheDocument();
    expect(screen.queryByText(/Preview workspace/)).not.toBeInTheDocument();
    expect(screen.queryByText(/0700000000/)).not.toBeInTheDocument();
  });

  it('keeps assigned parcels under All and Active, and filters/searches on live data', async () => {
    mount();
    await rows();
    const table = screen.getByRole('table');
    fireEvent.click(screen.getByRole('tab', { name: 'Unassigned' }));
    expect(within(table).getAllByRole('row').slice(1).map(row => row.textContent)).toEqual([expect.stringContaining('ZT-QUEUED0001')]);
    fireEvent.click(screen.getByRole('tab', { name: 'Active' }));
    expect(within(table).getAllByRole('row').slice(1).map(row => row.textContent)).toEqual([expect.stringContaining('ZT-ASSIGNED01')]);
    fireEvent.click(screen.getByRole('tab', { name: 'Delivered' }));
    expect(table).toHaveTextContent('ZT-DELIVERED1');
    fireEvent.click(screen.getByRole('tab', { name: 'Failed' }));
    expect(table).toHaveTextContent('No parcels match your filters and search.');
    fireEvent.click(screen.getByRole('tab', { name: 'All' }));
    fireEvent.change(screen.getByRole('textbox', { name: 'Search by reference or place' }), { target: { value: 'kodikamam' } });
    expect(within(table).getAllByRole('row')).toHaveLength(2);
    expect(table).toHaveTextContent('ZT-ASSIGNED01');
  });

  it('shows real details for the selected parcel and links unassigned parcels to the live queue', async () => {
    mount();
    await rows();
    fireEvent.click(screen.getByRole('button', { name: 'ZT-ASSIGNED01' }));
    const detailPanel = screen.getByRole('region', { name: 'Parcel detail' });
    expect(detailPanel).toHaveTextContent('Zentro Courier 1');
    expect(detailPanel).toHaveTextContent('Awaiting courier acceptance');
    expect(detailPanel).toHaveTextContent('Medium Box');
    expect(detailPanel).toHaveTextContent('10 kg');
    expect(within(detailPanel).queryByRole('link', { name: 'Assign in Dispatch Queue' })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: 'ZT-QUEUED0001' }));
    expect(within(detailPanel).getByRole('link', { name: 'Assign in Dispatch Queue' })).toHaveAttribute('href', '/dispatcher/queue');
  });

  it('reports an API failure with Retry instead of showing 0 parcels', async () => {
    failures.add('/api/v1/dispatch/assignments');
    mount();
    expect(await screen.findByRole('alert')).toHaveTextContent('Parcels unavailable');
    expect(screen.getByText('Parcel totals unavailable')).toBeInTheDocument();
    expect(screen.queryByText(/0 parcels/)).not.toBeInTheDocument();
    failures.clear();
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    expect(await screen.findByText('3 parcels · 1 unassigned')).toBeInTheDocument();
  });

  it('keeps a parcel visible and warns when only its S2 details fail', async () => {
    failures.add(`/api/v1/parcels/${ASSIGNED}`);
    mount();
    const table = await screen.findByRole('table');
    expect(await screen.findByText(/Details could not be loaded for 1 parcel\./)).toBeInTheDocument();
    expect(table).toHaveTextContent('Reference unavailable');
    expect(table).toHaveTextContent('Zentro Courier 1');
    expect(screen.getByText('3 parcels · 1 unassigned')).toBeInTheDocument();
  });
});

describe('deriveParcelStatus', () => {
  const held = { parcelId: ASSIGNED, courierId: COURIER, courierName: 'C', assignedAt: null };
  it('prefers S2 progress over assignment state', () => {
    expect(deriveParcelStatus('OUT_FOR_DELIVERY', false, { ...held, assignmentStatus: 'ACCEPTED' })).toBe('Out for delivery');
    expect(deriveParcelStatus('FAILED', false, undefined)).toBe('Failed');
  });
  it('treats queued or rejected CREATED parcels as unassigned', () => {
    expect(deriveParcelStatus('CREATED', true, undefined)).toBe('Unassigned');
    expect(deriveParcelStatus('CREATED', false, { ...held, assignmentStatus: 'REJECTED' })).toBe('Unassigned');
    expect(deriveParcelStatus(undefined, false, { ...held, assignmentStatus: 'PENDING_ACCEPTANCE' })).toBe('Assigned');
  });
});
