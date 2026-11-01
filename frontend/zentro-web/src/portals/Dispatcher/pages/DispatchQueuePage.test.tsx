import { beforeEach, describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { apiClient } from '@/shared/api/apiClient';
import { AuthContext } from '@/shared/auth/authContext';
import { DispatchQueuePage } from './DispatchQueuePage';
import { DispatchQueueCard } from './DispatchQueueCard';
import type { DispatchQueue } from '../api/dispatchApi';

const parcelId = '11111111-1111-4111-8111-111111111111';
const courierId = '22222222-2222-4222-8222-222222222222';
const parcel = { parcelId, trackingReference: 'TRACK-ONE', pickupSummary: 'Colombo', dropoffSummary: 'Kandy', createdAt: '2026-10-31T10:00:00Z' };
const courier = { id: courierId, userId: '33333333-3333-4333-8333-333333333333', name: 'Test Courier', active: true, accountStatus: 'ACTIVE' };
let queue: DispatchQueue;
let couriers: typeof courier[];

function mount(card = false) {
  return render(<QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } })}>
    <AuthContext.Provider value={{ user: { id: 'dispatcher', accessToken: 'test' }, loading: false } as never}>
      <MemoryRouter>{card && <DispatchQueueCard />}<DispatchQueuePage /></MemoryRouter>
    </AuthContext.Provider>
  </QueryClientProvider>);
}

async function openDialog() {
  fireEvent.click(await screen.findByRole('button', { name: 'Assign TRACK-ONE' }));
  return screen.findByRole('dialog');
}

async function chooseCourier() {
  await screen.findByRole('option', { name: /Test Courier/ });
  fireEvent.change(screen.getByLabelText('Courier'), { target: { value: courierId } });
}

describe('Dispatch Queue (mocked HTTP, real React Query)', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    queue = { items: [parcel], total: 1, matchingTotal: 1, complete: true };
    couriers = [courier];
    vi.spyOn(apiClient, 'get').mockImplementation(async url => ({ data: url === '/api/v1/dispatch/eligible-couriers' ? couriers : queue }) as never);
  });

  it('shows loading and prevents repeated refresh while pending', () => {
    vi.mocked(apiClient.get).mockReturnValue(new Promise(() => {}));
    mount();
    expect(screen.getByText('Loading dispatch queue…')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Refreshing…' })).toBeDisabled();
  });

  it('renders rows and an accurate overview count from the S3 response', async () => {
    mount(true);
    expect(await screen.findByRole('table')).toHaveTextContent('TRACK-ONE');
    expect(screen.getByRole('table')).toHaveTextContent('Colombo');
    expect(screen.getByRole('table')).toHaveTextContent('Kandy');
    expect(screen.getByRole('link', { name: /Dispatch queue/ })).toHaveTextContent('1');
  });

  it('shows Retry and manual Refresh without pretending errors mean an empty queue', async () => {
    vi.mocked(apiClient.get).mockRejectedValue(new Error('Unavailable'));
    mount();
    expect(await screen.findByRole('alert')).toHaveTextContent('queue total is unavailable');
    fireEvent.click(screen.getByRole('button', { name: 'Retry' }));
    await waitFor(() => expect(apiClient.get).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.getByRole('button', { name: 'Refresh' })).toBeEnabled());
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }));
    await waitFor(() => expect(apiClient.get).toHaveBeenCalledTimes(3));
    expect(screen.queryByText('The dispatch queue is empty.')).not.toBeInTheDocument();
  });

  it('rejects an unknown successful response', async () => {
    vi.mocked(apiClient.get).mockResolvedValue({ data: [] });
    mount(); expect(await screen.findByRole('alert')).toHaveTextContent('Dispatch queue unavailable');
  });

  it('distinguishes empty queue, no matching results and incomplete source', async () => {
    queue = { items: [], total: 0, matchingTotal: 0, complete: true };
    mount(); expect(await screen.findByText('The dispatch queue is empty.')).toBeInTheDocument();
    queue = { items: [], total: 1, matchingTotal: 0, complete: true };
    fireEvent.change(screen.getByLabelText('Search'), { target: { value: 'unmatched' } });
    fireEvent.click(screen.getByRole('button', { name: 'Apply filters' }));
    expect(await screen.findByText('No deliveries match your filters.')).toBeInTheDocument();
    queue = { items: [], total: null, matchingTotal: null, complete: false };
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }));
    expect(await screen.findByText('No deliveries are available in the current source snapshot.')).toBeInTheDocument();
    expect(screen.getByText(/Queue total unavailable/)).toBeInTheDocument();
  });

  it('sends only supported search/date/sort filters and validates dates', async () => {
    mount(); await screen.findByRole('table');
    fireEvent.change(screen.getByLabelText('Search'), { target: { value: '  Kandy  ' } });
    fireEvent.change(screen.getByLabelText('Created from (UTC)'), { target: { value: '2026-10-31' } });
    fireEvent.change(screen.getByLabelText('Created to (UTC)'), { target: { value: '2026-10-30' } });
    expect(screen.getByRole('button', { name: 'Apply filters' })).toBeDisabled();
    fireEvent.change(screen.getByLabelText('Created to (UTC)'), { target: { value: '2026-10-31' } });
    fireEvent.change(screen.getByLabelText('Sort'), { target: { value: 'oldest' } });
    fireEvent.click(screen.getByRole('button', { name: 'Apply filters' }));
    await waitFor(() => expect(apiClient.get).toHaveBeenLastCalledWith('/api/v1/dispatch/queue', {
      headers: { Authorization: 'Bearer test' }, params: { search: 'Kandy', from: '2026-10-31', to: '2026-10-31', sort: 'oldest' },
    }));
  });

  it('shows no eligible couriers without enabling confirmation', async () => {
    couriers = []; mount(); await openDialog();
    expect(await screen.findByText(/No eligible couriers are available/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Confirm assignment' })).toBeDisabled();
  });

  it('contains keyboard focus, supports Escape and restores the Assign button focus', async () => {
    mount(); const button = await screen.findByRole('button', { name: 'Assign TRACK-ONE' }); button.focus();
    const dialog = await openDialog(); await screen.findByLabelText('Courier');
    expect(dialog).toHaveAttribute('aria-modal', 'true');
    expect(dialog).toHaveFocus();
    fireEvent.keyDown(dialog, { key: 'Tab', shiftKey: true });
    expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
    fireEvent.keyDown(dialog, { key: 'Escape' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument(); expect(button).toHaveFocus();
  });

  it('requires explicit selection, prevents duplicate submits and refreshes rows/counts after success', async () => {
    let finish!: () => void;
    const post = vi.spyOn(apiClient, 'post').mockImplementation(() => new Promise(resolve => { finish = () => { queue = { items: [], total: 0, matchingTotal: 0, complete: true }; resolve({ data: {} }); }; }));
    mount(true); await openDialog();
    expect(screen.getByRole('button', { name: 'Confirm assignment' })).toBeDisabled();
    await chooseCourier();
    const confirm = screen.getByRole('button', { name: 'Confirm assignment' });
    fireEvent.click(confirm); fireEvent.click(confirm);
    await waitFor(() => expect(post).toHaveBeenCalledTimes(1));
    expect(screen.getByRole('button', { name: 'Assigning…' })).toBeDisabled();
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' }); expect(screen.getByRole('dialog')).toBeInTheDocument();
    finish();
    expect(await screen.findByText('Delivery assigned successfully.')).toBeInTheDocument();
    expect(await screen.findByText('The dispatch queue is empty.')).toBeInTheDocument();
    expect(screen.queryByText('TRACK-ONE')).not.toBeInTheDocument();
    expect(post).toHaveBeenCalledWith('/api/v1/dispatch/assignments', { parcelId, courierId }, { headers: { Authorization: 'Bearer test' } });
  });

  it('handles an already-assigned delivery by closing the dialog and refreshing', async () => {
    vi.spyOn(apiClient, 'post').mockImplementation(async () => {
      queue = { items: [], total: 0, matchingTotal: 0, complete: true };
      throw { isAxiosError: true, response: { status: 409, data: { code: 'DELIVERY_ALREADY_ASSIGNED' } } };
    });
    mount(); await openDialog(); await chooseCourier(); fireEvent.click(screen.getByRole('button', { name: 'Confirm assignment' }));
    expect(await screen.findByText(/This delivery is already assigned or no longer eligible/)).toBeInTheDocument();
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument());
    expect(await screen.findByText('The dispatch queue is empty.')).toBeInTheDocument();
  });

  it('clears an ineligible courier selection and keeps the dialog available', async () => {
    vi.spyOn(apiClient, 'post').mockRejectedValue({ isAxiosError: true, response: { status: 409, data: { code: 'COURIER_INELIGIBLE' } } });
    mount(); await openDialog(); await chooseCourier(); fireEvent.click(screen.getByRole('button', { name: 'Confirm assignment' }));
    expect(await screen.findByText('That courier is no longer eligible. Choose another courier.')).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Confirm assignment' })).toBeDisabled();
  });

  it('disables a stale courier selection after manual refresh removes it', async () => {
    mount(); await openDialog(); await chooseCourier(); couriers = [];
    fireEvent.click(screen.getByRole('button', { name: 'Refresh couriers' }));
    expect(await screen.findByText(/No eligible couriers are available/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Confirm assignment' })).toBeDisabled();
  });

  it('supports courier loading errors and Retry', async () => {
    vi.mocked(apiClient.get).mockImplementation(async url => {
      if (url === '/api/v1/dispatch/eligible-couriers') throw new Error('Offline');
      return { data: queue } as never;
    });
    mount(); const dialog = await openDialog();
    expect(await within(dialog).findByText('Could not load eligible couriers.')).toBeInTheDocument();
    vi.mocked(apiClient.get).mockResolvedValue({ data: couriers });
    fireEvent.click(screen.getByRole('button', { name: 'Retry couriers' }));
    expect(await screen.findByRole('option', { name: /Test Courier/ })).toBeInTheDocument();
  });

  it('shows assignment failure without a success notice or automatic resubmission', async () => {
    const post = vi.spyOn(apiClient, 'post').mockRejectedValue({ isAxiosError: true, response: { status: 503 } });
    mount(); await openDialog(); await chooseCourier(); fireEvent.click(screen.getByRole('button', { name: 'Confirm assignment' }));
    expect(await screen.findByText(/Assignment failed/)).toBeInTheDocument();
    expect(post).toHaveBeenCalledTimes(1); expect(screen.queryByText('Delivery assigned successfully.')).not.toBeInTheDocument();
  });
});
