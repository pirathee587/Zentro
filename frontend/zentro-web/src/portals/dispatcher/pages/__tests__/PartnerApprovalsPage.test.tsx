import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, expect, it, vi, beforeAll, afterAll, afterEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { AuthContext, type Auth } from '@/shared/auth/authContext';
import { MerchantApprovalsPage } from '../MerchantApprovalsPage';
import type { CourierProfile } from '../../api/courierAdminApi';
import type { MerchantApplication } from '../../api/adminApi';

vi.mock('@/shared/auth/oidcConfig', () => ({
  clearLegacyStoredTokens: vi.fn(),
  oidcManager: {
    getUser: vi.fn().mockResolvedValue(null),
    signinSilent: vi.fn().mockRejectedValue(new Error('login_required')),
    events: {
      addUserLoaded: vi.fn(),
      removeUserLoaded: vi.fn(),
      addUserUnloaded: vi.fn(),
      removeUserUnloaded: vi.fn(),
    },
  },
}));

// ─── Fixtures ─────────────────────────────────────────────────────────────────
const DISPATCHER_AUTH: Auth['user'] = {
  id: 'dispatcher-1',
  name: 'Chief Dispatcher',
  roles: ['DISPATCHER'],
  accessToken: 'dispatcher-jwt-token',
  accountStatus: 'ACTIVE',
  profileLoaded: true,
};

const NON_DISPATCHER_AUTH: Auth['user'] = {
  id: 'customer-1',
  name: 'Regular Customer',
  roles: ['CUSTOMER'],
  accessToken: 'customer-jwt-token',
  accountStatus: 'ACTIVE',
  profileLoaded: true,
};

const MOCK_MERCHANTS: MerchantApplication[] = [
  {
    id: 'merch-1',
    appUserId: 'user-m1',
    businessName: 'Apex Logistics LLC',
    businessRegNo: 'REG-1001',
    contactPhone: '+94 77 111 2222',
    status: 'PENDING',
    rejectionReason: null,
    createdAt: '2026-02-01T08:00:00Z',
    reviewedAt: null,
  },
  {
    id: 'merch-2',
    appUserId: 'user-m2',
    businessName: 'Zenitha Stores',
    businessRegNo: 'REG-2002',
    contactPhone: '+94 77 333 4444',
    status: 'APPROVED',
    rejectionReason: null,
    createdAt: '2026-01-10T09:00:00Z',
    reviewedAt: '2026-01-12T10:00:00Z',
  },
];

const MOCK_COURIERS: CourierProfile[] = [
  {
    id: 'courier-pending-1',
    userId: 'user-c1',
    email: 'kamal.swift@example.com',
    name: 'Kamal Perera',
    phone: '+94 71 555 6666',
    active: false, // Inactive = Pending approval
    accountStatus: 'ACTIVE',
    assignedZone: 'Central Zone',
    vehicleType: 'MOTORCYCLE',
    createdAt: '2026-02-02T11:00:00Z',
    updatedAt: '2026-02-02T11:00:00Z',
  },
  {
    id: 'courier-active-2',
    userId: 'user-c2',
    email: 'nimal.fleet@example.com',
    name: 'Nimal Silva',
    phone: '+94 71 888 9999',
    active: true, // Active = Approved fleet
    accountStatus: 'ACTIVE',
    assignedZone: 'North Zone',
    vehicleType: 'VAN',
    createdAt: '2026-01-15T09:00:00Z',
    updatedAt: '2026-01-16T14:00:00Z',
  },
];

// ─── MSW Server ───────────────────────────────────────────────────────────────
const server = setupServer(
  http.get('*/api/v1/admin/merchants', () => HttpResponse.json(MOCK_MERCHANTS)),
  http.patch('*/api/v1/admin/merchants/:id', async ({ params, request }) => {
    const body = (await request.clone().json()) as { decision: string; rejectionReason?: string };
    const found = MOCK_MERCHANTS.find((m) => m.id === params['id']);
    return HttpResponse.json({
      ...(found || MOCK_MERCHANTS[0]),
      status: body.decision,
      rejectionReason: body.rejectionReason ?? null,
      reviewedAt: new Date().toISOString(),
    });
  }),
  http.get('*/api/v1/admin/couriers', () => HttpResponse.json(MOCK_COURIERS)),
  http.patch('*/api/v1/admin/couriers/:id', async ({ params, request }) => {
    const body = (await request.clone().json()) as { active?: boolean };
    const found = MOCK_COURIERS.find((c) => c.id === params['id']);
    return HttpResponse.json({
      ...(found || MOCK_COURIERS[0]),
      active: body.active !== undefined ? body.active : true,
      updatedAt: new Date().toISOString(),
    });
  }),
);

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterAll(() => server.close());
afterEach(() => server.resetHandlers());

// ─── Helpers ──────────────────────────────────────────────────────────────────
function makeQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

function renderPage(auth: Auth['user'] = DISPATCHER_AUTH) {
  const qc = makeQueryClient();
  const value: Auth = {
    user: auth,
    setUser: () => undefined,
    login: async () => undefined,
    register: async () => undefined,
    logout: async () => undefined,
    loading: false,
  };
  return render(
    <QueryClientProvider client={qc}>
      <AuthContext.Provider value={value}>
        <MemoryRouter>
          <MerchantApprovalsPage />
        </MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>,
  );
}

// ─── Tests ────────────────────────────────────────────────────────────────────
describe('PartnerApprovalsPage (ZD-083)', () => {
  it('renders merchant applications tab by default', async () => {
    renderPage();
    expect(await screen.findByText('Apex Logistics LLC')).toBeInTheDocument();
    expect(screen.getByText('Zenitha Stores')).toBeInTheDocument();
  });

  it('switches to Couriers tab and renders pending and active courier fleet profiles', async () => {
    renderPage();
    await screen.findByText('Apex Logistics LLC');

    const courierTab = screen.getByRole('tab', { name: /Couriers/i });
    fireEvent.click(courierTab);

    expect(await screen.findByText('Kamal Perera')).toBeInTheDocument();
    expect(screen.getByText('Nimal Silva')).toBeInTheDocument();
    expect(screen.getByText('MOTORCYCLE')).toBeInTheDocument();
    expect(screen.getByText('Central Zone')).toBeInTheDocument();
  });

  it('switches to All Partners tab and displays both merchants and couriers', async () => {
    renderPage();
    await screen.findByText('Apex Logistics LLC');

    const allTab = screen.getByRole('tab', { name: /All Partners/i });
    fireEvent.click(allTab);

    expect(await screen.findByText('Apex Logistics LLC')).toBeInTheDocument();
    expect(await screen.findByText('Kamal Perera')).toBeInTheDocument();
  });

  it('filters applications by status (Pending, Approved)', async () => {
    renderPage();
    await screen.findByText('Apex Logistics LLC');

    const statusSelect = screen.getByLabelText(/Filter by approval status/i);
    fireEvent.change(statusSelect, { target: { value: 'PENDING' } });

    expect(screen.getByText('Apex Logistics LLC')).toBeInTheDocument();
    expect(screen.queryByText('Zenitha Stores')).not.toBeInTheDocument();

    fireEvent.change(statusSelect, { target: { value: 'APPROVED' } });
    expect(screen.getByText('Zenitha Stores')).toBeInTheDocument();
    expect(screen.queryByText('Apex Logistics LLC')).not.toBeInTheDocument();
  });

  it('filters applications by search query', async () => {
    renderPage();
    await screen.findByText('Apex Logistics LLC');

    const searchInput = screen.getByLabelText(/Search partner applications/i);
    fireEvent.change(searchInput, { target: { value: 'Zenitha' } });

    expect(screen.getByText('Zenitha Stores')).toBeInTheDocument();
    expect(screen.queryByText('Apex Logistics LLC')).not.toBeInTheDocument();
  });

  it('opens and closes the partner details inspection modal', async () => {
    renderPage();
    await screen.findByText('Apex Logistics LLC');

    const detailButtons = screen.getAllByRole('button', { name: /View details/i });
    fireEvent.click(detailButtons[0]);

    expect(await screen.findByRole('dialog')).toBeInTheDocument();
    expect(screen.getByText('Business Verification')).toBeInTheDocument();
    expect(screen.getAllByText('REG-1001').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('user-m1')).toBeInTheDocument();

    const closeBtn = screen.getByLabelText(/Close details/i);
    fireEvent.click(closeBtn);

    await waitFor(() => {
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
  });

  it('approves a pending courier application successfully', async () => {
    renderPage();
    await screen.findByText('Apex Logistics LLC');

    fireEvent.click(screen.getByRole('tab', { name: /Couriers/i }));
    await screen.findByText('Kamal Perera');

    const approveCourierBtn = screen.getByRole('button', { name: /^Approve$/i });
    fireEvent.click(approveCourierBtn);

    expect(await screen.findByRole('status')).toHaveTextContent(
      /Courier Kamal Perera has been approved and activated successfully/i,
    );
  });

  it('deactivates an approved courier successfully', async () => {
    renderPage();
    await screen.findByText('Apex Logistics LLC');

    fireEvent.click(screen.getByRole('tab', { name: /Couriers/i }));
    await screen.findByText('Nimal Silva');

    const deactivateBtn = screen.getByRole('button', { name: /^Deactivate$/i });
    fireEvent.click(deactivateBtn);

    expect(await screen.findByRole('status')).toHaveTextContent(
      /Courier Nimal Silva has been deactivated successfully/i,
    );
  });

  it('handles courier API error gracefully', async () => {
    server.use(
      http.get('*/api/v1/admin/couriers', () =>
        HttpResponse.json({ message: 'Internal error' }, { status: 500 }),
      ),
    );
    renderPage();
    fireEvent.click(screen.getByRole('tab', { name: /Couriers/i }));

    expect(
      await screen.findByText(/Could not load courier profiles. Please try again./i),
    ).toBeInTheDocument();
  });

  it('shows empty state when no couriers are found', async () => {
    server.use(http.get('*/api/v1/admin/couriers', () => HttpResponse.json([])));
    renderPage();
    fireEvent.click(screen.getByRole('tab', { name: /Couriers/i }));

    expect(
      await screen.findByText(/No courier applications are waiting for review./i),
    ).toBeInTheDocument();
  });
});
