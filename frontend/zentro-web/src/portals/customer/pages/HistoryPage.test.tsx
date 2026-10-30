import React from 'react';
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent, act } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import axios from 'axios';
import { HistoryPage } from './HistoryPage';
import * as customerApi from '../services/customerApi';
import type { Parcel, ListParcelsResponse } from '../services/customerApi';

const mockParcels: Parcel[] = [
  {
    id: 'pcl-001',
    trackingRef: 'ZTR-SD8941K2M7',
    status: 'In progress',
    sender: {
      name: 'Amara Fernando',
      phone: '+94 77 123 4567',
      address: '42 Galle Road',
      city: 'Colombo 04',
    },
    recipient: {
      name: 'Kasun Perera',
      phone: '+94 71 987 6543',
      address: '15 Peradeniya Road',
      city: 'Kandy',
    },
    parcel: {
      description: 'Handcrafted Ceylon tea ceramic canister',
      weightKg: 1.8,
      category: 'Care Package',
    },
    fee: 1072,
    formattedFee: 'LKR 1,072',
    createdAt: '2026-10-27T08:30:00.000Z',
    formattedCreatedAt: '27 Oct 2026',
    timeline: [],
  },
  {
    id: 'pcl-002',
    trackingRef: 'ZTR-SD5512B8X9',
    status: 'Delivered',
    sender: {
      name: 'Amara Fernando',
      phone: '+94 77 123 4567',
      address: '42 Galle Road',
      city: 'Colombo 04',
    },
    recipient: {
      name: 'Nimali Senanayake',
      phone: '+94 76 333 8899',
      address: '88 Beach Road',
      city: 'Negombo',
    },
    parcel: {
      description: 'Document package',
      weightKg: 0.6,
      category: 'Documents',
    },
    fee: 650,
    formattedFee: 'LKR 650',
    createdAt: '2026-10-24T10:00:00.000Z',
    formattedCreatedAt: '24 Oct 2026',
    deliveredAt: '2026-10-25T16:30:00.000Z',
    formattedDeliveredAt: '25 Oct 2026',
    timeline: [],
  },
  {
    id: 'pcl-004',
    trackingRef: 'ZTR-SD9912W1Q4',
    status: 'Failed',
    failureReason: 'Incorrect address, recipient unreachable after 3 attempts',
    sender: {
      name: 'Amara Fernando',
      phone: '+94 77 123 4567',
      address: '42 Galle Road',
      city: 'Colombo 04',
    },
    recipient: {
      name: 'Sunil Wickramasinghe',
      phone: '+94 75 222 0000',
      address: 'Non-existent address lane 4',
      city: 'Jaffna',
    },
    parcel: {
      description: 'Hand tool accessories',
      weightKg: 3.1,
      category: 'Hardware',
    },
    fee: 1850,
    formattedFee: 'LKR 1,850',
    createdAt: '2026-10-20T09:00:00.000Z',
    formattedCreatedAt: '20 Oct 2026',
    timeline: [
      {
        id: 'ev-31',
        title: 'Delivery Failed',
        description: 'Incorrect address, recipient unreachable after 3 attempts',
        timestamp: '2026-10-22T17:00:00.000Z',
        formattedDate: '22 Oct 2026',
        location: 'Jaffna Hub',
        status: 'failed',
      },
    ],
  },
];

const mockListResponse: ListParcelsResponse = {
  parcels: mockParcels,
  total: 3,
  totalSpent: 3572,
  formattedTotalSpent: 'LKR 3,572',
};

describe('HistoryPage Component', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  afterEach(() => {
    vi.useRealTimers();
  });

  it('renders H1 "Delivery history" and subtitle "{n} parcels • LKR {total} total" from data', async () => {
    vi.spyOn(customerApi, 'listParcels').mockResolvedValue(mockListResponse);

    render(
      <MemoryRouter>
        <HistoryPage />
      </MemoryRouter>
    );

    expect(screen.getByRole('heading', { level: 1, name: 'Delivery history' })).toBeInTheDocument();

    await waitFor(() => {
      expect(screen.getByText(/3 parcels • LKR 3,572 total/i)).toBeInTheDocument();
    });
  });

  it('renders filter chips with aria-pressed and updates active chip style', async () => {
    const listSpy = vi.spyOn(customerApi, 'listParcels').mockResolvedValue(mockListResponse);

    render(
      <MemoryRouter>
        <HistoryPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('ZTR-SD8941K2M7')).toBeInTheDocument();
    });

    const allChip = screen.getByRole('button', { name: 'All' });
    const inProgressChip = screen.getByRole('button', { name: 'In progress' });
    const deliveredChip = screen.getByRole('button', { name: 'Delivered' });
    const failedChip = screen.getByRole('button', { name: 'Failed' });

    expect(allChip).toHaveAttribute('aria-pressed', 'true');
    expect(allChip).toHaveClass('is-active');
    expect(inProgressChip).toHaveAttribute('aria-pressed', 'false');

    // Click "In progress"
    fireEvent.click(inProgressChip);

    await waitFor(() => {
      expect(inProgressChip).toHaveAttribute('aria-pressed', 'true');
      expect(inProgressChip).toHaveClass('is-active');
      expect(allChip).toHaveAttribute('aria-pressed', 'false');
      expect(listSpy).toHaveBeenCalledWith(
        expect.objectContaining({ status: 'In progress', page: 1 })
      );
    });

    // Click "Delivered"
    fireEvent.click(deliveredChip);

    await waitFor(() => {
      expect(deliveredChip).toHaveAttribute('aria-pressed', 'true');
      expect(listSpy).toHaveBeenCalledWith(
        expect.objectContaining({ status: 'Delivered', page: 1 })
      );
    });

    // Click "Failed"
    fireEvent.click(failedChip);

    await waitFor(() => {
      expect(failedChip).toHaveAttribute('aria-pressed', 'true');
      expect(listSpy).toHaveBeenCalledWith(
        expect.objectContaining({ status: 'Failed', page: 1 })
      );
    });
  });

  it('debounces search by 300ms when typing tracking number or place', async () => {
    vi.useFakeTimers();
    const listSpy = vi.spyOn(customerApi, 'listParcels').mockResolvedValue(mockListResponse);

    render(
      <MemoryRouter>
        <HistoryPage />
      </MemoryRouter>
    );

    // Initial load
    expect(listSpy).toHaveBeenCalledTimes(1);

    const searchInput = screen.getByLabelText('Search deliveries');
    fireEvent.change(searchInput, { target: { value: 'Kandy' } });

    // Immediately after typing, should NOT have called listParcels yet
    expect(listSpy).toHaveBeenCalledTimes(1);

    // Advance 150ms
    act(() => {
      vi.advanceTimersByTime(150);
    });
    expect(listSpy).toHaveBeenCalledTimes(1);

    // Advance past 300ms
    act(() => {
      vi.advanceTimersByTime(200);
    });
    expect(listSpy).toHaveBeenCalledTimes(2);
    expect(listSpy).toHaveBeenLastCalledWith(
      expect.objectContaining({ q: 'Kandy', page: 1 })
    );
  });

  it('renders table columns, responsive classes, and row actions', async () => {
    vi.spyOn(customerApi, 'listParcels').mockResolvedValue(mockListResponse);

    const { container } = render(
      <MemoryRouter>
        <HistoryPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('ZTR-SD8941K2M7')).toBeInTheDocument();
    });

    // Headers
    expect(screen.getByText('Tracking no.')).toBeInTheDocument();
    expect(screen.getByText('Route')).toBeInTheDocument();
    expect(screen.getByText('Date')).toBeInTheDocument();
    expect(screen.getByText('Status')).toBeInTheDocument();
    expect(screen.getByText('Fee')).toBeInTheDocument();
    expect(screen.getByText('Actions')).toBeInTheDocument();

    // Responsive classes on Route and Date
    const routeHeaders = container.querySelectorAll('.sd-col-route');
    const dateHeaders = container.querySelectorAll('.sd-col-date');
    expect(routeHeaders.length).toBeGreaterThanOrEqual(1);
    expect(dateHeaders.length).toBeGreaterThanOrEqual(1);

    // StatusChip in table rows
    expect(container.querySelectorAll('.sd-status-chip').length).toBe(3);
    expect(screen.getAllByText('In progress').length).toBeGreaterThanOrEqual(2);
    expect(screen.getAllByText('Delivered').length).toBeGreaterThanOrEqual(2);
    expect(screen.getAllByText('Failed').length).toBeGreaterThanOrEqual(2);

    // Track button on all rows
    const trackButtons = screen.getAllByRole('button', { name: 'Track' });
    expect(trackButtons.length).toBe(3);

    // Receipt button ONLY on delivered parcel (1 delivered row)
    const receiptButtons = screen.getAllByRole('button', { name: 'Receipt' });
    expect(receiptButtons.length).toBe(1);
  });

  it('shows failure reason in a secondary line on failed rows', async () => {
    vi.spyOn(customerApi, 'listParcels').mockResolvedValue(mockListResponse);

    render(
      <MemoryRouter>
        <HistoryPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('ZTR-SD9912W1Q4')).toBeInTheDocument();
    });

    const failureReasonText = screen.getByText(
      /Failed: Incorrect address, recipient unreachable after 3 attempts/i
    );
    expect(failureReasonText).toBeInTheDocument();
    expect(failureReasonText).toHaveClass('sd-history-failed-reason');
  });

  it('supports "Load more" pagination using listParcels({ page, status, q })', async () => {
    const page1Parcels = [mockParcels[0], mockParcels[1]];
    const page2Parcels = [mockParcels[2]];

    const listSpy = vi
      .spyOn(customerApi, 'listParcels')
      .mockResolvedValueOnce({
        parcels: page1Parcels,
        total: 3,
        totalSpent: 3572,
        formattedTotalSpent: 'LKR 3,572',
      })
      .mockResolvedValueOnce({
        parcels: page2Parcels,
        total: 3,
        totalSpent: 3572,
        formattedTotalSpent: 'LKR 3,572',
      });

    render(
      <MemoryRouter>
        <HistoryPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('Showing 2 of 3 parcels')).toBeInTheDocument();
    });

    const loadMoreBtn = screen.getByRole('button', { name: 'Load more' });
    expect(loadMoreBtn).toBeInTheDocument();

    fireEvent.click(loadMoreBtn);

    await waitFor(() => {
      expect(listSpy).toHaveBeenCalledWith(
        expect.objectContaining({ page: 2 })
      );
      expect(screen.getByText('Showing 3 of 3 parcels')).toBeInTheDocument();
      expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument();
    });
  });

  it('does not append duplicate deliveries when loading more pages', async () => {
    const page1Parcels = [mockParcels[0], mockParcels[1]];
    // Page 2 returns mockParcels[1] again (duplicate) and mockParcels[2]
    const page2Parcels = [mockParcels[1], mockParcels[2]];

    vi.spyOn(customerApi, 'listParcels')
      .mockResolvedValueOnce({
        parcels: page1Parcels,
        total: 3,
      })
      .mockResolvedValueOnce({
        parcels: page2Parcels,
        total: 3,
      });

    render(
      <MemoryRouter>
        <HistoryPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('Showing 2 of 3 parcels')).toBeInTheDocument();
    });

    const loadMoreBtn = screen.getByRole('button', { name: 'Load more' });
    fireEvent.click(loadMoreBtn);

    await waitFor(() => {
      // Should show 3 parcels total, not 4
      expect(screen.getByText('Showing 3 of 3 parcels')).toBeInTheDocument();
    });
  });

  it('discards stale search/filter responses when newer requests resolve first (race conditions)', async () => {
    let resolveFirstRequest: (value: ListParcelsResponse) => void = () => {};
    const firstRequestPromise = new Promise<ListParcelsResponse>((resolve) => {
      resolveFirstRequest = resolve;
    });

    const listSpy = vi.spyOn(customerApi, 'listParcels');
    // First call (initial render) is slow
    listSpy.mockReturnValueOnce(firstRequestPromise);

    render(
      <MemoryRouter>
        <HistoryPage />
      </MemoryRouter>
    );

    // User switches filter to "Delivered", which resolves immediately
    const deliveredResult: ListParcelsResponse = {
      parcels: [mockParcels[1]],
      total: 1,
      totalSpent: 650,
      formattedTotalSpent: 'LKR 650',
    };
    listSpy.mockResolvedValueOnce(deliveredResult);

    const deliveredChip = screen.getByRole('button', { name: 'Delivered' });
    fireEvent.click(deliveredChip);

    await waitFor(() => {
      expect(screen.getByText('ZTR-SD5512B8X9')).toBeInTheDocument();
    });

    // Now resolve the older, initial request with 3 parcels
    act(() => {
      resolveFirstRequest(mockListResponse);
    });

    // The older response must be discarded; the table should still show only the Delivered parcel
    await waitFor(() => {
      expect(screen.getByText('ZTR-SD5512B8X9')).toBeInTheDocument();
      expect(screen.queryByText('ZTR-SD8941K2M7')).not.toBeInTheDocument();
      expect(screen.getByText(/1 parcels • LKR 650 total/i)).toBeInTheDocument();
    });
  });

  it('shows empty state ("No parcels match") when no records match filter and allows clearing filters', async () => {
    vi.spyOn(customerApi, 'listParcels').mockResolvedValue({
      parcels: [],
      total: 0,
      totalSpent: 0,
    });

    render(
      <MemoryRouter>
        <HistoryPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('No parcels match')).toBeInTheDocument();
    });

    // Select Delivered filter to reveal "Clear filters" button
    const deliveredChip = screen.getByRole('button', { name: 'Delivered' });
    fireEvent.click(deliveredChip);

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'Clear filters' })).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: 'Clear filters' }));

    await waitFor(() => {
      expect(screen.getByRole('button', { name: 'All' })).toHaveClass('is-active');
    });
  });

  it('renders loading skeleton rows during initial fetch', async () => {
    vi.spyOn(customerApi, 'listParcels').mockReturnValue(new Promise(() => {}));

    const { container } = render(
      <MemoryRouter>
        <HistoryPage />
      </MemoryRouter>
    );

    const skeletonRows = container.querySelectorAll('.sd-table-skeleton-row');
    expect(skeletonRows.length).toBeGreaterThanOrEqual(1);
  });

  it('renders error state with retry button on network failure', async () => {
    const listSpy = vi
      .spyOn(customerApi, 'listParcels')
      .mockRejectedValueOnce(new Error('Network error'))
      .mockResolvedValueOnce(mockListResponse);

    render(
      <MemoryRouter>
        <HistoryPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('Unable to load deliveries')).toBeInTheDocument();
    });

    const retryBtn = screen.getByRole('button', { name: 'Retry' });
    expect(retryBtn).toBeInTheDocument();

    fireEvent.click(retryBtn);

    await waitFor(() => {
      expect(listSpy).toHaveBeenCalledTimes(2);
      expect(screen.getByText('ZTR-SD8941K2M7')).toBeInTheDocument();
    });
  });

  it('handles 401 session expiration error with sign in action', async () => {
    const authError = new axios.AxiosError('Unauthorized', '401', undefined, undefined, {
      status: 401,
      statusText: 'Unauthorized',
      data: { message: 'Authentication required' },
      headers: {},
      config: {} as never,
    });

    vi.spyOn(customerApi, 'listParcels').mockRejectedValueOnce(authError);

    render(
      <MemoryRouter>
        <HistoryPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText(/Your session has expired\. Please sign in again\./i)).toBeInTheDocument();
    });

    expect(screen.getByRole('button', { name: 'Sign in again' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });

  it('renders gracefully with fallback when sender/recipient/dates are missing', async () => {
    const sparseParcel: Parcel = {
      id: 'pcl-sparse',
      trackingRef: 'ZTR-SPARSE01',
      status: 'Created',
      sender: { name: '', phone: '', address: '', city: '' },
      recipient: { name: '', phone: '', address: '', city: '' },
      parcel: { description: '', weightKg: 1, category: '' },
      fee: 0,
      formattedFee: '',
      createdAt: '',
      formattedCreatedAt: '',
      timeline: [],
    };

    vi.spyOn(customerApi, 'listParcels').mockResolvedValueOnce({
      parcels: [sparseParcel],
      total: 1,
    });

    render(
      <MemoryRouter>
        <HistoryPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('ZTR-SPARSE01')).toBeInTheDocument();
      expect(screen.getByText('Origin → Destination')).toBeInTheDocument();
    });
  });
});
