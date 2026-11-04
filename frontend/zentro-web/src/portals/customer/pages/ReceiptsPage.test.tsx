import React from 'react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { ReceiptsPage } from './ReceiptsPage';
import * as customerApi from '../services/customerApi';
import type { Parcel, Receipt } from '../services/customerApi';

const mockDeliveredParcel1: Parcel = {
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
    size: 'Small',
  },
  fee: 650,
  formattedFee: 'LKR 650',
  createdAt: '2026-10-24T10:00:00.000Z',
  formattedCreatedAt: '24 Oct 2026',
  deliveredAt: '2026-10-25T16:30:00.000Z',
  formattedDeliveredAt: '25 Oct 2026',
  timeline: [],
};

const mockDeliveredParcel2: Parcel = {
  id: 'pcl-005',
  trackingRef: 'ZTR-SD1122C3D4',
  status: 'Delivered',
  sender: {
    name: 'Amara Fernando',
    phone: '+94 77 123 4567',
    address: '42 Galle Road',
    city: 'Colombo 04',
  },
  recipient: {
    name: 'Ruwan Silva',
    phone: '+94 71 111 2222',
    address: '5 Station Road',
    city: 'Galle',
  },
  parcel: {
    description: 'Specialty coffee beans canister',
    weightKg: 2.4,
    category: 'Care Package',
    size: 'Medium',
  },
  fee: 1250,
  formattedFee: 'LKR 1,250',
  createdAt: '2026-10-22T08:00:00.000Z',
  formattedCreatedAt: '22 Oct 2026',
  deliveredAt: '2026-10-23T15:00:00.000Z',
  formattedDeliveredAt: '23 Oct 2026',
  timeline: [],
};

const mockDeliveredParcelWithPOD: Parcel = {
  id: 'pcl-008',
  trackingRef: 'ZTR-SD8822P9X1',
  status: 'Delivered',
  sender: {
    name: 'Kasun Bandara',
    phone: '+94 77 555 1234',
    address: '10 Peradeniya Rd',
    city: 'Kandy',
  },
  recipient: {
    name: 'Dilani Perera',
    phone: '+94 71 888 9999',
    address: '15 High Level Rd',
    city: 'Maharagama',
  },
  parcel: {
    description: 'Medical supplies box',
    weightKg: 1.5,
    category: 'Medical',
    size: 'Medium',
  },
  fee: 950,
  formattedFee: 'LKR 950',
  createdAt: '2026-10-26T09:00:00.000Z',
  formattedCreatedAt: '26 Oct 2026',
  deliveredAt: '2026-10-27T14:20:00.000Z',
  formattedDeliveredAt: '27 Oct 2026',
  proofOfDelivery: {
    signedBy: 'D. Perera (Recipient)',
    timestamp: '27 Oct 2026, 14:20',
    notes: 'Received in good condition at security desk',
  },
  timeline: [],
};

const mockFailedParcel: Parcel = {
  id: 'pcl-004',
  trackingRef: 'ZTR-SD9912W1Q4',
  status: 'Failed',
  failureReason: 'Incorrect address, recipient unreachable',
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
    description: 'Hardware tools',
    weightKg: 3.1,
    category: 'Hardware',
    size: 'Medium',
  },
  fee: 1850,
  formattedFee: 'LKR 1,850',
  createdAt: '2026-10-20T09:00:00.000Z',
  formattedCreatedAt: '20 Oct 2026',
  timeline: [],
};

const mockInProgressParcel: Parcel = {
  id: 'pcl-009',
  trackingRef: 'ZTR-SD3344T5K6',
  status: 'In progress',
  sender: {
    name: 'Amara Fernando',
    phone: '+94 77 123 4567',
    address: '42 Galle Road',
    city: 'Colombo 04',
  },
  recipient: {
    name: 'Janaka Perera',
    phone: '+94 71 333 4444',
    address: '12 Main St',
    city: 'Matara',
  },
  parcel: {
    description: 'Books package',
    weightKg: 1.2,
    category: 'Standard',
    size: 'Medium',
  },
  fee: 800,
  formattedFee: 'LKR 800',
  createdAt: '2026-10-27T11:00:00.000Z',
  formattedCreatedAt: '27 Oct 2026',
  timeline: [],
};

const mockReceipts: Receipt[] = [
  {
    id: 'rec-002',
    receiptNumber: 'REC-2026-1024-02',
    trackingRef: 'ZTR-SD5512B8X9',
    amount: 650,
    formattedAmount: 'LKR 650',
    date: '2026-10-25T16:30:00.000Z',
    formattedDate: '25 Oct 2026',
    paymentMethod: 'Online Bank Transfer',
    paymentStatus: 'Paid',
    downloadUrl: '#receipt-1024-02',
    senderName: 'Amara Fernando',
    recipientName: 'Nimali Senanayake',
  },
  {
    id: 'rec-005',
    receiptNumber: 'REC-2026-1022-05',
    trackingRef: 'ZTR-SD1122C3D4',
    amount: 1250,
    formattedAmount: 'LKR 1,250',
    date: '2026-10-23T15:00:00.000Z',
    formattedDate: '23 Oct 2026',
    paymentMethod: 'Credit / Debit Card',
    paymentStatus: 'Paid',
    downloadUrl: '#receipt-1022-05',
    senderName: 'Amara Fernando',
    recipientName: 'Ruwan Silva',
  },
];

describe('ReceiptsPage Component', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('renders two columns: left card (span 5) "Delivered" and right card (span 7) receipt paper preview', async () => {
    vi.spyOn(customerApi, 'listParcels').mockResolvedValue({
      parcels: [mockDeliveredParcel1, mockDeliveredParcel2],
      total: 2,
    });
    vi.spyOn(customerApi, 'listReceipts').mockResolvedValue(mockReceipts);

    const { container } = render(
      <MemoryRouter initialEntries={['/customer/receipts']}>
        <ReceiptsPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('Delivered')).toBeInTheDocument();
      expect(screen.getByText('Receipt preview')).toBeInTheDocument();
    });

    const leftCard = container.querySelector('.sd-receipt-left-col');
    const rightCard = container.querySelector('.sd-receipt-right-col');
    expect(leftCard).toHaveClass('sd-col-span-5');
    expect(rightCard).toHaveClass('sd-col-span-7');

    const paper = container.querySelector('.sd-receipt-paper');
    expect(paper).toBeInTheDocument();
  });

  it('lists delivered parcels with tracking no • destination on left, fee on right, and selected row has is-selected class', async () => {
    vi.spyOn(customerApi, 'listParcels').mockResolvedValue({
      parcels: [mockDeliveredParcel1, mockDeliveredParcel2],
      total: 2,
    });
    vi.spyOn(customerApi, 'listReceipts').mockResolvedValue(mockReceipts);

    const { container } = render(
      <MemoryRouter initialEntries={['/customer/receipts']}>
        <ReceiptsPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getAllByText('ZTR-SD5512B8X9').length).toBeGreaterThanOrEqual(1);
      expect(screen.getByText('• Negombo')).toBeInTheDocument();
      expect(screen.getAllByText('ZTR-SD1122C3D4').length).toBeGreaterThanOrEqual(1);
      expect(screen.getByText('• Galle')).toBeInTheDocument();
    });

    const rows = container.querySelectorAll('.sd-receipt-list-row');
    expect(rows.length).toBe(2);
    expect(rows[0]).toHaveClass('is-selected');
    expect(rows[0]).toHaveAttribute('aria-selected', 'true');
    expect(rows[1]).not.toHaveClass('is-selected');
  });

  it('renders booking reference, separated dates, breakdown, and proof of delivery rows', async () => {
    vi.spyOn(customerApi, 'listParcels').mockResolvedValue({
      parcels: [mockDeliveredParcel1],
      total: 1,
    });
    vi.spyOn(customerApi, 'listReceipts').mockResolvedValue([mockReceipts[0]]);

    const { container } = render(
      <MemoryRouter initialEntries={['/customer/receipts']}>
        <ReceiptsPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('SwiftDrop')).toBeInTheDocument();
      expect(screen.getByText(/Delivery receipt • RC-2026-1024-02/i)).toBeInTheDocument();
    });

    // Required fields
    expect(screen.getByText('Tracking number')).toBeInTheDocument();
    expect(screen.getByText('Booking reference')).toBeInTheDocument();
    expect(screen.getByText('Booking date')).toBeInTheDocument();
    expect(screen.getByText('Delivery completion date')).toBeInTheDocument();
    expect(screen.getByText('From')).toBeInTheDocument();
    expect(screen.getByText('To')).toBeInTheDocument();
    expect(screen.getByText('Parcel size and weight')).toBeInTheDocument();
    expect(screen.getByText('Base fee')).toBeInTheDocument();
    expect(screen.getByText('Weight charge')).toBeInTheDocument();
    expect(screen.getByText('Distance surcharge')).toBeInTheDocument();
    expect(screen.getByText('Payment method')).toBeInTheDocument();
    expect(screen.getByText('Proof of delivery')).toBeInTheDocument();

    // Values
    expect(screen.getByText('pcl-002')).toBeInTheDocument();
    expect(screen.getByText('24 Oct 2026')).toBeInTheDocument();
    expect(screen.getByText('25 Oct 2026')).toBeInTheDocument();
    expect(screen.getByText('Colombo 04')).toBeInTheDocument();
    expect(screen.getByText('Negombo')).toBeInTheDocument();
    expect(screen.getByText('Small (0.6 kg)')).toBeInTheDocument();
    expect(screen.getByText('LKR 550')).toBeInTheDocument();
    expect(screen.getByText('LKR 0')).toBeInTheDocument();
    expect(screen.getByText('LKR 100')).toBeInTheDocument();
    expect(screen.getByText(/Online Bank Transfer \(•••• 8821\)/i)).toBeInTheDocument();

    // Missing proof state
    expect(screen.getByText('Unavailable')).toBeInTheDocument();
    expect(
      screen.getByText('Electronic signature or photo proof was not recorded for this delivery')
    ).toBeInTheDocument();

    // Total row
    const totalRow = container.querySelector('.sd-receipt-total-row');
    expect(totalRow).toBeInTheDocument();
    expect(screen.getByText('Total')).toBeInTheDocument();
    expect(totalRow?.textContent).toContain('LKR 650');
  });

  it('renders verified Proof of Delivery summary when proof data is present', async () => {
    vi.spyOn(customerApi, 'listParcels').mockResolvedValue({
      parcels: [mockDeliveredParcelWithPOD],
      total: 1,
    });
    vi.spyOn(customerApi, 'listReceipts').mockResolvedValue([]);

    render(
      <MemoryRouter initialEntries={['/customer/receipts']}>
        <ReceiptsPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getAllByText('ZTR-SD8822P9X1').length).toBeGreaterThanOrEqual(1);
    });

    expect(screen.getByText('Verified')).toBeInTheDocument();
    expect(screen.getByText('D. Perera (Recipient)')).toBeInTheDocument();
    expect(
      screen.getByText('Note: Received in good condition at security desk')
    ).toBeInTheDocument();
  });

  it('renders "Print / Save as PDF" accent button calling window.print()', async () => {
    vi.spyOn(customerApi, 'listParcels').mockResolvedValue({
      parcels: [mockDeliveredParcel1],
      total: 1,
    });
    vi.spyOn(customerApi, 'listReceipts').mockResolvedValue([mockReceipts[0]]);

    const printSpy = vi.fn();
    window.print = printSpy;

    render(
      <MemoryRouter initialEntries={['/customer/receipts']}>
        <ReceiptsPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Print \/ Save as PDF/i })).toBeInTheDocument();
    });

    const printBtn = screen.getByRole('button', { name: /Print \/ Save as PDF/i });
    expect(printBtn).toHaveClass('sd-btn-accent');

    fireEvent.click(printBtn);
    expect(printSpy).toHaveBeenCalledTimes(1);
  });

  it('preselects receipt passed from query param ?trackingRef=...', async () => {
    vi.spyOn(customerApi, 'listParcels').mockResolvedValue({
      parcels: [mockDeliveredParcel1, mockDeliveredParcel2],
      total: 2,
    });
    vi.spyOn(customerApi, 'listReceipts').mockResolvedValue(mockReceipts);

    const { container } = render(
      <MemoryRouter initialEntries={['/customer/receipts?trackingRef=ZTR-SD1122C3D4']}>
        <Routes>
          <Route path="/customer/receipts" element={<ReceiptsPage />} />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getAllByText('ZTR-SD1122C3D4').length).toBeGreaterThanOrEqual(1);
    });

    const rows = container.querySelectorAll('.sd-receipt-list-row');
    expect(rows[0]).not.toHaveClass('is-selected');
    expect(rows[1]).toHaveClass('is-selected');
    expect(rows[1]).toHaveAttribute('aria-selected', 'true');

    expect(screen.getByText(/Delivery receipt • RC-2026-1022-05/i)).toBeInTheDocument();
    expect(screen.getByText('Galle')).toBeInTheDocument();
  });

  it('switches selected receipt when clicking another delivered parcel row', async () => {
    vi.spyOn(customerApi, 'listParcels').mockResolvedValue({
      parcels: [mockDeliveredParcel1, mockDeliveredParcel2],
      total: 2,
    });
    vi.spyOn(customerApi, 'listReceipts').mockResolvedValue(mockReceipts);

    const { container } = render(
      <MemoryRouter initialEntries={['/customer/receipts']}>
        <ReceiptsPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getAllByText('ZTR-SD5512B8X9').length).toBeGreaterThanOrEqual(1);
    });

    const rows = container.querySelectorAll('.sd-receipt-list-row');
    expect(rows[0]).toHaveClass('is-selected');

    fireEvent.click(rows[1]);

    await waitFor(() => {
      expect(rows[0]).not.toHaveClass('is-selected');
      expect(rows[1]).toHaveClass('is-selected');
      expect(screen.getByText('Galle')).toBeInTheDocument();
    });
  });

  it('excludes failed deliveries from the receipts delivered list', async () => {
    vi.spyOn(customerApi, 'listParcels').mockResolvedValue({
      parcels: [mockDeliveredParcel1, mockFailedParcel],
      total: 2,
    });
    vi.spyOn(customerApi, 'listReceipts').mockResolvedValue(mockReceipts);

    render(
      <MemoryRouter initialEntries={['/customer/receipts']}>
        <ReceiptsPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getAllByText('ZTR-SD5512B8X9').length).toBeGreaterThanOrEqual(1);
    });

    expect(screen.queryByText('ZTR-SD9912W1Q4')).not.toBeInTheDocument();
    expect(screen.queryByText(/Incorrect address/i)).not.toBeInTheDocument();
  });

  it('handles query param ?trackingRef for an in-progress parcel without silently selecting another receipt', async () => {
    vi.spyOn(customerApi, 'listParcels').mockResolvedValue({
      parcels: [mockDeliveredParcel1],
      total: 1,
    });
    vi.spyOn(customerApi, 'listReceipts').mockResolvedValue([mockReceipts[0]]);
    vi.spyOn(customerApi, 'getParcel').mockResolvedValue(mockInProgressParcel);

    const { container } = render(
      <MemoryRouter initialEntries={['/customer/receipts?trackingRef=ZTR-SD3344T5K6']}>
        <ReceiptsPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('Delivery in progress — Receipt unavailable')).toBeInTheDocument();
    });

    expect(
      screen.getByText(
        /Parcel ZTR-SD3344T5K6 is currently "In progress"\. Official payment receipts and delivery summaries are only generated once delivery is completed\./i
      )
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /Track parcel \(ZTR-SD3344T5K6\)/i })).toHaveAttribute(
      'href',
      '/customer/track/ZTR-SD3344T5K6'
    );

    // No row in the left delivered list should be marked as selected
    const rows = container.querySelectorAll('.sd-receipt-list-row');
    expect(rows[0]).not.toHaveClass('is-selected');

    // Clicking "View delivered receipts" switches to the first delivered parcel
    const viewBtn = screen.getByRole('button', { name: 'View delivered receipts' });
    fireEvent.click(viewBtn);

    await waitFor(() => {
      expect(rows[0]).toHaveClass('is-selected');
      expect(screen.getByText('SwiftDrop')).toBeInTheDocument();
    });
  });

  it('handles query param ?trackingRef for an unknown reference without silently selecting another receipt', async () => {
    vi.spyOn(customerApi, 'listParcels').mockResolvedValue({
      parcels: [mockDeliveredParcel1],
      total: 1,
    });
    vi.spyOn(customerApi, 'listReceipts').mockResolvedValue([mockReceipts[0]]);
    vi.spyOn(customerApi, 'getParcel').mockResolvedValue(null);

    const { container } = render(
      <MemoryRouter initialEntries={['/customer/receipts?trackingRef=ZTR-UNKNOWN999']}>
        <ReceiptsPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('Delivery receipt not found')).toBeInTheDocument();
    });

    expect(
      screen.getByText(/We could not locate any delivery matching tracking reference "ZTR-UNKNOWN999"/i)
    ).toBeInTheDocument();

    const rows = container.querySelectorAll('.sd-receipt-list-row');
    expect(rows[0]).not.toHaveClass('is-selected');
  });

  it('handles unauthorized (401/403) access by displaying an authentication required empty state', async () => {
    const authError = new Error('Unauthorized');
    (authError as unknown as { status: number }).status = 401;

    vi.spyOn(customerApi, 'listParcels').mockRejectedValueOnce(authError);

    render(
      <MemoryRouter initialEntries={['/customer/receipts']}>
        <ReceiptsPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('Authentication required')).toBeInTheDocument();
    });

    expect(
      screen.getByText('You must be signed in as an authorized customer to view your delivery receipts.')
    ).toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Sign In' })).toHaveAttribute('href', '/login');
  });

  it('shows empty state ("No delivered parcels yet") when no delivered parcels exist', async () => {
    vi.spyOn(customerApi, 'listParcels').mockResolvedValue({
      parcels: [],
      total: 0,
    });
    vi.spyOn(customerApi, 'listReceipts').mockResolvedValue([]);

    render(
      <MemoryRouter initialEntries={['/customer/receipts']}>
        <ReceiptsPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('No delivered parcels yet')).toBeInTheDocument();
    });
  });

  it('shows error state with retry button on service failure', async () => {
    const listSpy = vi
      .spyOn(customerApi, 'listParcels')
      .mockRejectedValueOnce(new Error('Connection failure'))
      .mockResolvedValueOnce({
        parcels: [mockDeliveredParcel1],
        total: 1,
      });
    vi.spyOn(customerApi, 'listReceipts').mockResolvedValue(mockReceipts);

    render(
      <MemoryRouter initialEntries={['/customer/receipts']}>
        <ReceiptsPage />
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('Unable to load receipts')).toBeInTheDocument();
    });

    const retryBtn = screen.getByRole('button', { name: 'Retry' });
    expect(retryBtn).toBeInTheDocument();

    fireEvent.click(retryBtn);

    await waitFor(() => {
      expect(listSpy).toHaveBeenCalledTimes(2);
      expect(screen.getAllByText('ZTR-SD5512B8X9').length).toBeGreaterThanOrEqual(1);
    });
  });
});
