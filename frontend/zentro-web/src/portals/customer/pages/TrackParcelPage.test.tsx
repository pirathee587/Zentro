import React from 'react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { TrackView } from '../components/TrackView';
import { PublicTrackPage } from './PublicTrackPage';
import { TrackParcelPage } from './index';
import { ToastProvider } from '../components/Toast';
import * as customerApi from '../services/customerApi';
import type { Parcel } from '../services/customerApi';

const mockInProgressParcel: Parcel = {
  id: 'pcl-001',
  trackingRef: 'ZTR-SD8941K2M7',
  status: 'In progress',
  sender: {
    name: 'Amara Fernando',
    phone: '+94 77 123 4567',
    address: '42 Galle Road, Bambalapitiya',
    city: 'Colombo 04',
  },
  recipient: {
    name: 'Kasun Perera',
    phone: '+94 71 987 6543',
    address: '15 Peradeniya Road',
    city: 'Kandy',
  },
  parcel: {
    description: 'Handcrafted Ceylon tea ceramic canister & spice box',
    weightKg: 1.8,
    category: 'Care Package',
    size: 'Medium',
  },
  fee: 1072,
  formattedFee: 'LKR 1,072',
  createdAt: '2026-10-27T08:30:00.000Z',
  formattedCreatedAt: 'Oct 27, 2026, 08:30 AM',
  estimatedDeliveryDate: '2026-10-29T14:00:00.000Z',
  formattedEstimatedDeliveryDate: 'today, 4:30 PM',
  courier: {
    name: 'Nuwan Jayasuriya',
    vehicle: 'Delivery Van (WP CAS-8821)',
    zone: 'Colombo Metro & Western',
    phone: '+94 77 800 1234',
  },
  timeline: [
    {
      id: 'ev-1',
      title: 'Booking Confirmed',
      description: 'Delivery requested and assigned reference ZTR-SD8941K2M7',
      timestamp: '2026-10-27T08:30:00.000Z',
      formattedDate: 'Oct 27, 2026, 08:30 AM',
      location: 'Colombo 04',
      status: 'completed',
    },
    {
      id: 'ev-2',
      title: 'Parcel Picked Up',
      description: 'Courier collected parcel from sender doorstep',
      timestamp: '2026-10-27T11:15:00.000Z',
      formattedDate: 'Oct 27, 2026, 11:15 AM',
      location: 'Colombo Dispatch Hub',
      status: 'completed',
    },
    {
      id: 'ev-3',
      title: 'In Transit to Regional Hub',
      description: 'On route via Central Expressway dispatch line',
      timestamp: '2026-10-27T15:45:00.000Z',
      formattedDate: 'Oct 27, 2026, 03:45 PM',
      location: 'Kadawatha Interchange',
      status: 'current',
    },
    {
      id: 'ev-4',
      title: 'Out for Delivery',
      description: 'Assigned to Kandy courier for final mile delivery',
      timestamp: '2026-10-28T09:00:00.000Z',
      formattedDate: 'Oct 28, 2026',
      location: 'Kandy Central Hub',
      status: 'upcoming',
    },
    {
      id: 'ev-5',
      title: 'Delivered',
      description: 'Recipient signature and delivery confirmation',
      timestamp: '2026-10-29T14:00:00.000Z',
      formattedDate: 'Oct 29, 2026',
      location: 'Kandy',
      status: 'upcoming',
    },
  ],
};

const mockDeliveredParcel: Parcel = {
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
  formattedCreatedAt: 'Oct 24, 2026',
  deliveredAt: '2026-10-25T16:30:00.000Z',
  formattedDeliveredAt: 'Oct 25, 2026, 04:30 PM',
  timeline: [
    {
      id: 'ev-11',
      title: 'Delivered to Recipient',
      description: 'Signed by recipient at main reception',
      timestamp: '2026-10-25T16:30:00.000Z',
      formattedDate: 'Oct 25, 2026, 04:30 PM',
      location: 'Negombo',
      status: 'completed',
    },
  ],
};

const mockFailedParcel: Parcel = {
  id: 'pcl-004',
  trackingRef: 'ZTR-SD9912W1Q4',
  status: 'Failed',
  failureReason: 'Incorrect address, recipient unreachable after 3 attempts',
  sender: {
    name: 'Amara Fernando',
    phone: '+94 77 123 4567',
    address: '42 Galle Road, Bambalapitiya',
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
    size: 'Medium',
  },
  fee: 1850,
  formattedFee: 'LKR 1,850',
  createdAt: '2026-10-20T09:00:00.000Z',
  formattedCreatedAt: 'Oct 20, 2026',
  timeline: [
    {
      id: 'ev-31',
      title: 'Delivery Failed',
      description: 'Incorrect address, recipient unreachable after 3 attempts',
      timestamp: '2026-10-22T17:00:00.000Z',
      formattedDate: 'Oct 22, 2026',
      location: 'Jaffna Hub',
      status: 'failed',
    },
  ],
};

describe('TrackView and Tracking Pages', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
    vi.spyOn(customerApi, 'subscribeToParcel').mockImplementation(() => {
      return () => {};
    });
  });

  it('renders search card with input, track button, and submits on Enter key', async () => {
    const getParcelSpy = vi.spyOn(customerApi, 'getParcel').mockResolvedValue(mockInProgressParcel);

    render(
      <MemoryRouter initialEntries={['/track']}>
        <ToastProvider>
          <TrackView />
        </ToastProvider>
      </MemoryRouter>
    );

    const input = screen.getByLabelText(/Tracking number/i);
    expect(input).toBeInTheDocument();
    const trackBtn = screen.getByRole('button', { name: /^Track$/i });
    expect(trackBtn).toBeInTheDocument();

    fireEvent.change(input, { target: { value: 'ZTR-SD8941K2M7' } });
    fireEvent.submit(input.closest('form')!);

    await waitFor(() => {
      expect(getParcelSpy).toHaveBeenCalledWith('ZTR-SD8941K2M7');
    });
  });

  it('shows "No parcel found for that number" error with role="alert" when parcel does not exist', async () => {
    vi.spyOn(customerApi, 'getParcel').mockResolvedValue(null);

    render(
      <MemoryRouter initialEntries={['/track']}>
        <ToastProvider>
          <TrackView />
        </ToastProvider>
      </MemoryRouter>
    );

    const input = screen.getByLabelText(/Tracking number/i);
    fireEvent.change(input, { target: { value: 'ZTR-SD00000000' } });
    fireEvent.click(screen.getByRole('button', { name: /^Track$/i }));

    await waitFor(() => {
      const alerts = screen.getAllByRole('alert');
      const hasNotFoundMsg = alerts.some((a) =>
        a.textContent?.includes('No parcel found for that number')
      );
      expect(hasNotFoundMsg).toBe(true);
    });
  });

  it('renders Parcel Card (span 7) with large tracking number, pulsing live indicator, route, status chip, and progress bar', async () => {
    vi.spyOn(customerApi, 'getParcel').mockResolvedValue(mockInProgressParcel);

    const { container } = render(
      <MemoryRouter initialEntries={['/track/ZTR-SD8941K2M7']}>
        <ToastProvider>
          <TrackView initialTrackingRef="ZTR-SD8941K2M7" />
        </ToastProvider>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('ZTR-SD8941K2M7')).toBeInTheDocument();
    });

    // Tracking number has large class
    const heading = screen.getByText('ZTR-SD8941K2M7');
    expect(heading).toHaveClass('sd-track-ref-large');

    // Live indicator
    expect(screen.getByText('Live')).toBeInTheDocument();
    const liveDot = container.querySelector('.sd-live-dot');
    expect(liveDot).toBeInTheDocument();

    // Status chip
    expect(screen.getByText('In progress')).toBeInTheDocument();

    // Route subtitle: "From to To • weight • size"
    const routeText = container.querySelector('.sd-route-subtitle');
    expect(routeText).toBeInTheDocument();
    expect(routeText?.textContent).toMatch(/Colombo 04 to Kandy.*1\.8 kg.*Medium/);

    // Route progress bar
    const progressbar = screen.getByRole('progressbar');
    expect(progressbar).toBeInTheDocument();
    const fill = container.querySelector('.sd-route-fill');
    expect(fill).toBeInTheDocument();
  });

  it('renders 5 stages timeline: done steps have --dark, current has glow, pending has "Pending"', async () => {
    vi.spyOn(customerApi, 'getParcel').mockResolvedValue(mockInProgressParcel);

    const { container } = render(
      <MemoryRouter initialEntries={['/track/ZTR-SD8941K2M7']}>
        <ToastProvider>
          <TrackView initialTrackingRef="ZTR-SD8941K2M7" />
        </ToastProvider>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('Created')).toBeInTheDocument();
      expect(screen.getByText('Picked up')).toBeInTheDocument();
      expect(screen.getByText('In transit')).toBeInTheDocument();
      expect(screen.getByText('Out for delivery')).toBeInTheDocument();
      expect(screen.getByText('Delivered')).toBeInTheDocument();
    });

    // Created and Picked up should be done
    const doneNodes = container.querySelectorAll('.sd-stage-node.is-done');
    expect(doneNodes.length).toBeGreaterThanOrEqual(2);

    // Connecting line for done steps
    const doneLines = container.querySelectorAll('.sd-stage-line.is-done');
    expect(doneLines.length).toBeGreaterThanOrEqual(1);

    // In transit should be current
    const currentNode = container.querySelector('.sd-stage-node.is-current');
    expect(currentNode).toBeInTheDocument();

    // Pending tags
    const pendingTags = screen.getAllByText('Pending');
    expect(pendingTags.length).toBeGreaterThanOrEqual(1);

    // Completed steps show date and time
    expect(screen.getByText('Oct 27, 2026, 08:30 AM')).toBeInTheDocument();
    expect(screen.getByText('Oct 27, 2026, 11:15 AM')).toBeInTheDocument();

    // ETA line below
    expect(screen.getByText(/today, 4:30 PM/i)).toBeInTheDocument();
  });

  it('renders failed delivery stage with reason and red-tinted marker, and failure reason in ETA line', async () => {
    vi.spyOn(customerApi, 'getParcel').mockResolvedValue(mockFailedParcel);

    const { container } = render(
      <MemoryRouter initialEntries={['/track/ZTR-SD9912W1Q4']}>
        <ToastProvider>
          <TrackView initialTrackingRef="ZTR-SD9912W1Q4" />
        </ToastProvider>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('Delivery failed')).toBeInTheDocument();
    });

    // Red-tinted failed marker
    const failedNode = container.querySelector('.sd-stage-node.is-failed');
    expect(failedNode).toBeInTheDocument();

    // Failure reason displayed under the stage
    const reasonElements = screen.getAllByText(
      /Incorrect address, recipient unreachable after 3 attempts/i
    );
    expect(reasonElements.length).toBeGreaterThanOrEqual(1);

    // Failed ETA line banner
    const etaBanner = container.querySelector('.sd-eta-banner.is-failed');
    expect(etaBanner).toBeInTheDocument();
  });

  it('renders Delivered parcel with "Delivered on {date}" ETA line', async () => {
    vi.spyOn(customerApi, 'getParcel').mockResolvedValue(mockDeliveredParcel);

    render(
      <MemoryRouter initialEntries={['/track/ZTR-SD5512B8X9']}>
        <ToastProvider>
          <TrackView initialTrackingRef="ZTR-SD5512B8X9" />
        </ToastProvider>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText(/Delivered on Oct 25, 2026/i)).toBeInTheDocument();
    });
  });

  it('renders Right column (span 5): Your courier panel and Share tracking link card', async () => {
    vi.spyOn(customerApi, 'getParcel').mockResolvedValue(mockInProgressParcel);

    const { container } = render(
      <MemoryRouter initialEntries={['/track/ZTR-SD8941K2M7']}>
        <ToastProvider>
          <TrackView initialTrackingRef="ZTR-SD8941K2M7" />
        </ToastProvider>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('Your courier')).toBeInTheDocument();
    });

    // Dark panel card for courier
    const courierCard = container.querySelector('.sd-card-panel');
    expect(courierCard).toBeInTheDocument();
    expect(screen.getByText('Nuwan Jayasuriya')).toBeInTheDocument();
    expect(screen.getByText('Delivery Van (WP CAS-8821)')).toBeInTheDocument();
    expect(screen.getByText(/Colombo Metro & Western/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Call courier/i })).toBeInTheDocument();

    // Share tracking link card
    expect(screen.getByText('Share tracking link')).toBeInTheDocument();
    const shareInput = screen.getByLabelText(/Public tracking link/i);
    expect(shareInput).toBeInTheDocument();
    expect((shareInput as HTMLInputElement).value).toContain('ZTR-SD8941K2M7');
    expect(screen.getByRole('button', { name: /Copy/i })).toBeInTheDocument();
  });

  it('handles clipboard copy and fallback text "Select the link and copy it" if clipboard is blocked', async () => {
    vi.spyOn(customerApi, 'getParcel').mockResolvedValue(mockInProgressParcel);

    // Mock clipboard.writeText rejecting to simulate clipboard permission denied/blocked
    const originalClipboard = navigator.clipboard;
    Object.defineProperty(navigator, 'clipboard', {
      value: {
        writeText: vi.fn().mockRejectedValue(new Error('Permission denied')),
      },
      configurable: true,
    });

    render(
      <MemoryRouter initialEntries={['/track/ZTR-SD8941K2M7']}>
        <ToastProvider>
          <TrackView initialTrackingRef="ZTR-SD8941K2M7" />
        </ToastProvider>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByRole('button', { name: /Copy/i })).toBeInTheDocument();
    });

    fireEvent.click(screen.getByRole('button', { name: /Copy/i }));

    await waitFor(() => {
      expect(screen.getByText('Select the link and copy it')).toBeInTheDocument();
    });

    // Restore clipboard
    Object.defineProperty(navigator, 'clipboard', {
      value: originalClipboard,
      configurable: true,
    });
  });

  it('subscribes to real-time updates and announces status changes via aria-live', async () => {
    let subscriberCallback: ((parcel: Parcel) => void) | undefined;
    vi.spyOn(customerApi, 'getParcel').mockResolvedValue(mockInProgressParcel);
    vi.spyOn(customerApi, 'subscribeToParcel').mockImplementation((_ref, cb) => {
      subscriberCallback = cb;
      return () => {};
    });

    const { container } = render(
      <MemoryRouter initialEntries={['/track/ZTR-SD8941K2M7']}>
        <ToastProvider>
          <TrackView initialTrackingRef="ZTR-SD8941K2M7" />
        </ToastProvider>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('ZTR-SD8941K2M7')).toBeInTheDocument();
    });

    expect(subscriberCallback).toBeDefined();

    // Trigger real-time update
    const updatedParcel: Parcel = {
      ...mockInProgressParcel,
      status: 'Delivered',
      formattedDeliveredAt: 'Oct 29, 2026, 02:15 PM',
    };

    subscriberCallback!(updatedParcel);

    await waitFor(() => {
      const liveRegion = container.querySelector('[aria-live="polite"]');
      expect(liveRegion?.textContent).toContain('Shipment status updated to Delivered.');
    });
  });

  it('displays error state with retry button on network failure', async () => {
    const getParcelSpy = vi
      .spyOn(customerApi, 'getParcel')
      .mockRejectedValueOnce(new Error('500 Service Error'))
      .mockResolvedValueOnce(mockInProgressParcel);

    render(
      <MemoryRouter initialEntries={['/track/ZTR-SD8941K2M7']}>
        <ToastProvider>
          <TrackView initialTrackingRef="ZTR-SD8941K2M7" />
        </ToastProvider>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('Unable to load tracking details')).toBeInTheDocument();
    });

    const retryBtn = screen.getByRole('button', { name: /Retry/i });
    expect(retryBtn).toBeInTheDocument();

    fireEvent.click(retryBtn);

    await waitFor(() => {
      expect(getParcelSpy).toHaveBeenCalledTimes(2);
      expect(screen.getByText('ZTR-SD8941K2M7')).toBeInTheDocument();
    });
  });

  it('protects privacy on public page: does not expose customer street addresses or phone numbers', async () => {
    vi.spyOn(customerApi, 'getParcel').mockResolvedValue(mockInProgressParcel);

    render(
      <MemoryRouter initialEntries={['/track/ZTR-SD8941K2M7']}>
        <Routes>
          <Route path="/track/:trackingRef" element={<PublicTrackPage />} />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('ZTR-SD8941K2M7')).toBeInTheDocument();
    });

    // City-level route is visible
    expect(screen.getByText(/Colombo 04 to Kandy/)).toBeInTheDocument();

    // Customer personal details MUST NOT be exposed
    expect(screen.queryByText(/42 Galle Road, Bambalapitiya/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/15 Peradeniya Road/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/\+94 77 123 4567/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/\+94 71 987 6543/i)).not.toBeInTheDocument();

    // Public page has no nav element
    expect(screen.queryByRole('navigation')).not.toBeInTheDocument();
  });

  it('renders customer portal TrackParcelPage within customer layout', async () => {
    vi.spyOn(customerApi, 'getParcel').mockResolvedValue(mockInProgressParcel);

    render(
      <MemoryRouter initialEntries={['/customer/track/ZTR-SD8941K2M7']}>
        <Routes>
          <Route path="/customer/track/:trackingRef" element={<TrackParcelPage />} />
        </Routes>
      </MemoryRouter>
    );

    await waitFor(() => {
      expect(screen.getByText('Track Parcel')).toBeInTheDocument();
      expect(screen.getByText('ZTR-SD8941K2M7')).toBeInTheDocument();
    });
  });
});
