import React from 'react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { CustomerDashboard } from './CustomerDashboard';
import * as customerApi from '../services/customerApi';
import type { DashboardData, Parcel } from '../services/customerApi';

const mockDashboardData: DashboardData = {
  greeting: 'Welcome back to SwiftDrop',
  userName: 'Amara Fernando',
  activeCount: 3,
  deliveredCount: 8,
  failedCount: 1,
  awaitingPickupCount: 2,
  inTransitCount: 1,
  totalParcels: 12,
  totalSpent: 9800,
  formattedTotalSpent: 'LKR 9,800',
  monthlySpent: 4200,
  formattedMonthlySpent: 'LKR 4,200',
  courier: {
    name: 'Nuwan Jayasuriya',
    initials: 'NJ',
    role: 'Senior Courier',
    zone: 'Colombo Metro',
    phone: '+94 77 800 1234',
  },
  recentParcels: [
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
        description: 'Ceylon tea box',
        weightKg: 1.5,
        category: 'Care Package',
      },
      fee: 1072,
      formattedFee: 'LKR 1,072',
      createdAt: '2026-10-27T08:30:00.000Z',
      formattedCreatedAt: 'Oct 27, 2026',
      estimatedDeliveryDate: '2026-10-29T14:00:00.000Z',
      formattedEstimatedDeliveryDate: 'Oct 29, 2026',
      timeline: [
        {
          id: 'ev-1',
          title: 'Booking Confirmed',
          timestamp: '2026-10-27T08:30:00.000Z',
          status: 'completed',
        },
        {
          id: 'ev-2',
          title: 'Parcel Picked Up',
          timestamp: '2026-10-27T11:15:00.000Z',
          status: 'completed',
        },
        {
          id: 'ev-3',
          title: 'In Transit to Regional Hub',
          timestamp: '2026-10-27T15:45:00.000Z',
          status: 'current',
        },
        {
          id: 'ev-4',
          title: 'Out for Delivery',
          timestamp: '2026-10-28T09:00:00.000Z',
          status: 'upcoming',
        },
        {
          id: 'ev-5',
          title: 'Delivered',
          timestamp: '2026-10-29T14:00:00.000Z',
          status: 'upcoming',
        },
      ],
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
        description: 'Architectural drawings',
        weightKg: 0.6,
        category: 'Documents',
      },
      fee: 550,
      formattedFee: 'LKR 550',
      createdAt: '2026-10-26T10:00:00.000Z',
      formattedCreatedAt: 'Oct 26, 2026',
      timeline: [],
    },
  ],
  notifications: [
    {
      id: 'notif-1',
      icon: 'truck',
      title: 'Parcel in transit',
      body: 'ZTR-SD8941K2M7 passed Kadawatha interchange',
      time: '12 min ago',
      isNew: true,
    },
    {
      id: 'notif-2',
      icon: 'check',
      title: 'Delivery confirmed',
      body: 'ZTR-SD5512B8X9 signed by Nimali S.',
      time: '3 hours ago',
      isNew: false,
    },
  ],
};

describe('CustomerDashboard Page', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  it('renders skeleton loading state initially', () => {
    vi.spyOn(customerApi, 'getDashboard').mockImplementation(
      () => new Promise(() => {}) // never resolves
    );

    render(
      <MemoryRouter>
        <CustomerDashboard />
      </MemoryRouter>
    );

    expect(screen.getByLabelText(/Loading Customer Dashboard/i)).toBeInTheDocument();
  });

  it('renders top row with greeting, segmented bar, monthly spend and 3 large stats', async () => {
    vi.spyOn(customerApi, 'getDashboard').mockResolvedValue(mockDashboardData);
    vi.spyOn(customerApi, 'estimateFee').mockResolvedValue({
      fee: 800,
      formattedFee: 'LKR 800',
      currency: 'LKR',
      estimatedDays: 1,
      breakdown: { baseFee: 550, weightFee: 250 },
    });

    render(
      <MemoryRouter>
        <CustomerDashboard />
      </MemoryRouter>
    );

    // H1 check
    expect(await screen.findByRole('heading', { level: 1, name: /Welcome back, Amara/i })).toBeInTheDocument();

    // Segmented bar checks
    expect(screen.getByText('Delivered', { selector: '.dash-seg-label' })).toBeInTheDocument();
    expect(screen.getByText('In transit', { selector: '.dash-seg-label' })).toBeInTheDocument();
    expect(screen.getByText('Awaiting', { selector: '.dash-seg-label' })).toBeInTheDocument();
    expect(screen.getByText('Failed', { selector: '.dash-seg-label' })).toBeInTheDocument();

    // Spend check
    expect(screen.getByText("This month's spend")).toBeInTheDocument();
    expect(screen.getByText('LKR 4,200')).toBeInTheDocument();

    // 3 Large stats check
    expect(screen.getByText('Active parcels')).toBeInTheDocument();
    expect(screen.getByText('Awaiting pickup')).toBeInTheDocument();
    expect(screen.getByText('3', { selector: '.dash-big-stat-value' })).toBeInTheDocument();
    expect(screen.getByText('8', { selector: '.dash-big-stat-value' })).toBeInTheDocument();
    expect(screen.getByText('2', { selector: '.dash-big-stat-value' })).toBeInTheDocument();
  });

  it('renders Courier card with avatar initials, name, and Call courier pill', async () => {
    vi.spyOn(customerApi, 'getDashboard').mockResolvedValue(mockDashboardData);

    render(
      <MemoryRouter>
        <CustomerDashboard />
      </MemoryRouter>
    );

    expect(await screen.findByText('Nuwan Jayasuriya')).toBeInTheDocument();
    expect(screen.getByText('NJ')).toBeInTheDocument();
    expect(screen.getByText(/Senior Courier/)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Call courier/i })).toBeInTheDocument();
  });

  it('renders Live tracking card with 5-step stepper and receipt button', async () => {
    vi.spyOn(customerApi, 'getDashboard').mockResolvedValue(mockDashboardData);

    render(
      <MemoryRouter>
        <CustomerDashboard />
      </MemoryRouter>
    );

    // Tracking number big and route
    expect(await screen.findByRole('heading', { level: 3, name: 'ZTR-SD8941K2M7' })).toBeInTheDocument();
    expect(screen.getByText(/Colombo 04 → Kandy/i)).toBeInTheDocument();

    // 5-step horizontal stepper
    expect(screen.getByText('Created', { selector: '.dash-step-label' })).toBeInTheDocument();
    expect(screen.getByText('Picked up', { selector: '.dash-step-label' })).toBeInTheDocument();
    expect(screen.getByText('In transit', { selector: '.dash-step-label' })).toBeInTheDocument();
    expect(screen.getByText('Out for delivery', { selector: '.dash-step-label' })).toBeInTheDocument();
    expect(screen.getByText('Delivered', { selector: '.dash-step-label' })).toBeInTheDocument();

    // Download receipt button linking to /customer/receipts
    const receiptBtn = screen.getByRole('button', { name: /Download receipt/i });
    expect(receiptBtn).toBeInTheDocument();
    expect(receiptBtn.closest('a')).toHaveAttribute('href', '/customer/receipts');
  });

  it('renders Quick fee estimate card and enables continuing to send', async () => {
    vi.spyOn(customerApi, 'getDashboard').mockResolvedValue(mockDashboardData);
    vi.spyOn(customerApi, 'estimateFee').mockResolvedValue({
      fee: 1250,
      formattedFee: 'LKR 1,250',
      currency: 'LKR',
      estimatedDays: 2,
      breakdown: { baseFee: 550, weightFee: 700 },
    });

    render(
      <MemoryRouter>
        <CustomerDashboard />
      </MemoryRouter>
    );

    expect(await screen.findByText('Quick Fee Estimate')).toBeInTheDocument();
    expect(await screen.findByText('LKR 1,250')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Continue to send/i })).toBeInTheDocument();
  });

  it('renders Delivery history card with parcels and View all link', async () => {
    vi.spyOn(customerApi, 'getDashboard').mockResolvedValue(mockDashboardData);

    render(
      <MemoryRouter>
        <CustomerDashboard />
      </MemoryRouter>
    );

    expect(await screen.findByText('Delivery History')).toBeInTheDocument();
    expect(screen.getByText('Negombo')).toBeInTheDocument();
    expect(screen.getByText('LKR 550')).toBeInTheDocument();
    const viewAllBtn = screen.getByRole('button', { name: /View all/i });
    expect(viewAllBtn.closest('a')).toHaveAttribute('href', '/customer/history');
  });

  it('allows tracking any parcel via the Track Any Parcel card', async () => {
    vi.spyOn(customerApi, 'getDashboard').mockResolvedValue(mockDashboardData);
    const mockLookupParcel: Parcel = {
      ...mockDashboardData.recentParcels[0],
      trackingRef: 'ZTR-SD9999K001',
      recipient: { name: 'Test', phone: '123', address: 'Road', city: 'Galle' },
    };
    vi.spyOn(customerApi, 'getParcel').mockResolvedValue(mockLookupParcel);

    render(
      <MemoryRouter>
        <CustomerDashboard />
      </MemoryRouter>
    );

    await screen.findByText('Track Any Parcel');
    const input = screen.getByPlaceholderText(/SD-20377|ZTR-SD/i);
    fireEvent.change(input, { target: { value: 'ZTR-SD9999K001' } });
    fireEvent.click(screen.getByRole('button', { name: /Track parcel/i }));

    expect(await screen.findByText(/Colombo 04 → Galle/i)).toBeInTheDocument();
    expect(screen.getByText('View full progress →')).toBeInTheDocument();
  });

  it('renders Notifications panel with rows and newest icon highlight', async () => {
    vi.spyOn(customerApi, 'getDashboard').mockResolvedValue(mockDashboardData);

    render(
      <MemoryRouter>
        <CustomerDashboard />
      </MemoryRouter>
    );

    expect(await screen.findByText('Notifications')).toBeInTheDocument();
    expect(screen.getByText('Parcel in transit')).toBeInTheDocument();
    expect(screen.getByText(/passed Kadawatha/)).toBeInTheDocument();
    expect(screen.getByText('12 min ago')).toBeInTheDocument();
  });

  it('renders empty state when customer has no parcels', async () => {
    vi.spyOn(customerApi, 'getDashboard').mockResolvedValue({
      ...mockDashboardData,
      totalParcels: 0,
      activeCount: 0,
      deliveredCount: 0,
      inTransitCount: 0,
      awaitingPickupCount: 0,
      recentParcels: [],
    });

    render(
      <MemoryRouter>
        <CustomerDashboard />
      </MemoryRouter>
    );

    expect(await screen.findByText('No parcels yet')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Send your first parcel/i })).toBeInTheDocument();
  });

  it('renders error card with retry button when loading fails', async () => {
    vi.spyOn(customerApi, 'getDashboard').mockRejectedValue(new Error('Network gateway timeout'));

    render(
      <MemoryRouter>
        <CustomerDashboard />
      </MemoryRouter>
    );

    expect(await screen.findByText('Unable to load dashboard')).toBeInTheDocument();
    expect(screen.getByText('Network gateway timeout')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Retry/i })).toBeInTheDocument();
  });
});
