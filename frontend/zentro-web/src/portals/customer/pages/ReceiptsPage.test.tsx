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

    // Left card span 5, right card span 7
    const leftCard = container.querySelector('.sd-receipt-left-col');
    const rightCard = container.querySelector('.sd-receipt-right-col');
    expect(leftCard).toHaveClass('sd-col-span-5');
    expect(rightCard).toHaveClass('sd-col-span-7');

    // Receipt paper element
    const paper = container.querySelector('.sd-receipt-paper');
    expect(paper).toBeInTheDocument();
  });

  it('lists delivered parcels with tracking no • destination on left, fee on right, and selected row has 1px --ink border', async () => {
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

    // First parcel should be selected by default
    const rows = container.querySelectorAll('.sd-receipt-list-row');
    expect(rows.length).toBe(2);
    expect(rows[0]).toHaveClass('is-selected');
    expect(rows[0]).toHaveAttribute('aria-selected', 'true');
    expect(rows[1]).not.toHaveClass('is-selected');
  });

  it('renders all required receipt paper fields with dotted dividers and larger Total row', async () => {
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

    // Field labels
    expect(screen.getByText('Tracking number')).toBeInTheDocument();
    expect(screen.getByText('Date')).toBeInTheDocument();
    expect(screen.getByText('From')).toBeInTheDocument();
    expect(screen.getByText('To')).toBeInTheDocument();
    expect(screen.getByText('Parcel size and weight')).toBeInTheDocument();
    expect(screen.getByText('Base fee')).toBeInTheDocument();
    expect(screen.getByText('Weight charge')).toBeInTheDocument();
    expect(screen.getByText('Distance surcharge')).toBeInTheDocument();
    expect(screen.getByText('Payment method')).toBeInTheDocument();

    // Field values
    expect(screen.getByText('Colombo 04')).toBeInTheDocument();
    expect(screen.getByText('Negombo')).toBeInTheDocument();
    expect(screen.getByText('Small (0.6 kg)')).toBeInTheDocument();
    expect(screen.getByText('LKR 550')).toBeInTheDocument();
    expect(screen.getByText('LKR 0')).toBeInTheDocument();
    expect(screen.getByText('LKR 100')).toBeInTheDocument();
    expect(screen.getByText(/Online Bank Transfer \(•••• 8821\)/i)).toBeInTheDocument();

    // Dotted dividers
    const lines = container.querySelectorAll('.sd-receipt-line');
    expect(lines.length).toBe(9);

    // Total row
    const totalRow = container.querySelector('.sd-receipt-total-row');
    expect(totalRow).toBeInTheDocument();
    expect(screen.getByText('Total')).toBeInTheDocument();
    expect(totalRow?.textContent).toContain('LKR 650');
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

    // Second parcel should be selected because trackingRef query param matched it
    const rows = container.querySelectorAll('.sd-receipt-list-row');
    expect(rows[0]).not.toHaveClass('is-selected');
    expect(rows[1]).toHaveClass('is-selected');
    expect(rows[1]).toHaveAttribute('aria-selected', 'true');

    // Receipt preview reflects parcel 2
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

    // Click row 2
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

    // Failed parcel must NOT be rendered
    expect(screen.queryByText('ZTR-SD9912W1Q4')).not.toBeInTheDocument();
    expect(screen.queryByText(/Incorrect address/i)).not.toBeInTheDocument();
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
