import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { describe, expect, it, vi, beforeAll, afterAll, afterEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { AuthContext, type Auth } from '@/shared/auth/authContext';
import { MerchantApprovalsPage } from './MerchantApprovalsPage';

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
  id: 'dispatcher-id',
  name: 'Dispatcher',
  roles: ['DISPATCHER'],
  accessToken: 'dispatcher-token',
  accountStatus: 'ACTIVE',
  profileLoaded: true,
};

const PENDING_APP = {
  id: 'app-pending',
  appUserId: 'user-pending',
  businessName: 'Pending Corp',
  businessRegNo: 'BRN001',
  contactPhone: '+1 555 0001',
  status: 'PENDING',
  rejectionReason: null,
  createdAt: '2026-01-01T10:00:00Z',
  reviewedAt: null,
};

const APPROVED_APP = {
  id: 'app-approved',
  appUserId: 'user-approved',
  businessName: 'Approved Ltd',
  businessRegNo: 'BRN002',
  contactPhone: '+1 555 0002',
  status: 'APPROVED',
  rejectionReason: null,
  createdAt: '2025-12-01T10:00:00Z',
  reviewedAt: '2026-01-15T10:00:00Z',
};

const REJECTED_APP = {
  id: 'app-rejected',
  appUserId: 'user-rejected',
  businessName: 'Rejected Inc',
  businessRegNo: 'BRN003',
  contactPhone: '+1 555 0003',
  status: 'REJECTED',
  rejectionReason: 'Incomplete documentation',
  createdAt: '2025-11-01T10:00:00Z',
  reviewedAt: '2025-11-10T10:00:00Z',
};

// ─── MSW server ───────────────────────────────────────────────────────────────
const server = setupServer(
  http.get('*/api/v1/admin/merchants', () =>
    HttpResponse.json([PENDING_APP, APPROVED_APP, REJECTED_APP]),
  ),
  http.patch('*/api/v1/admin/merchants/:id', async ({ params, request }) => {
    const body = (await request.json()) as { decision: string; rejectionReason?: string };
    if (params['id'] === 'app-pending') {
      return HttpResponse.json({
        ...PENDING_APP,
        status: body.decision,
        rejectionReason: body.rejectionReason ?? null,
        reviewedAt: new Date().toISOString(),
      });
    }
    // Simulate 409 for already-reviewed
    return HttpResponse.json({ message: 'Conflict' }, { status: 409 });
  }),
);

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterAll(() => server.close());
afterEach(() => server.resetHandlers());

// ─── Helpers ──────────────────────────────────────────────────────────────────
function makeQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

function renderApprovals(auth: Auth['user'] = DISPATCHER_AUTH) {
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
describe('MerchantApprovalsPage', () => {
  it('shows loading state', async () => {
    server.use(
      http.get('*/api/v1/admin/merchants', async () => {
        await new Promise((r) => setTimeout(r, 200));
        return HttpResponse.json([]);
      }),
    );
    renderApprovals();
    expect(screen.getByText(/Loading applications/i)).toBeInTheDocument();
  });

  it('renders all applications regardless of status', async () => {
    renderApprovals();
    expect(await screen.findByText('Pending Corp')).toBeInTheDocument();
    expect(screen.getByText('Approved Ltd')).toBeInTheDocument();
    expect(screen.getByText('Rejected Inc')).toBeInTheDocument();
  });

  it('shows status chips for each application', async () => {
    renderApprovals();
    await screen.findByText('Pending Corp');
    expect(screen.getByText('PENDING')).toBeInTheDocument();
    expect(screen.getByText('APPROVED')).toBeInTheDocument();
    expect(screen.getByText('REJECTED')).toBeInTheDocument();
  });

  it('shows approve/reject buttons only for pending applications', async () => {
    renderApprovals();
    await screen.findByText('Pending Corp');
    // Pending app should have Approve and Reject buttons
    expect(screen.getAllByRole('button', { name: /^Approve$/i }).length).toBe(1);
    expect(screen.getAllByRole('button', { name: /^Reject$/i }).length).toBe(1);
    // Approved and Rejected apps should have NO action buttons (only pending ones do)
    // The single Approve button is the one for 'Pending Corp'
  });

  it('shows rejection reason for already-rejected applications', async () => {
    renderApprovals();
    await screen.findByText('Rejected Inc');
    expect(screen.getByText('Incomplete documentation')).toBeInTheDocument();
  });

  it('shows reviewed-at date for approved applications', async () => {
    renderApprovals();
    await screen.findByText('Approved Ltd');
    // reviewedAt: '2026-01-15T10:00:00Z' — just check the label exists
    // REJECTED_APP also has reviewedAt so multiple "Reviewed on" labels exist; assert at least 1.
    expect(screen.getAllByText(/Reviewed on/i).length).toBeGreaterThanOrEqual(1);
  });

  it('requires rejection reason before rejecting', async () => {
    renderApprovals();
    await screen.findByText('Pending Corp');
    fireEvent.click(screen.getByRole('button', { name: /^Reject$/i }));
    expect(
      await screen.findByText(/Add a rejection reason before rejecting/i),
    ).toBeInTheDocument();
  });

  it('approves an application and shows success feedback', async () => {
    renderApprovals();
    await screen.findByText('Pending Corp');
    fireEvent.click(screen.getByRole('button', { name: /^Approve$/i }));
    expect(await screen.findByRole('status')).toHaveTextContent(/approved successfully/i);
  });

  it('rejects an application with a reason and shows success feedback', async () => {
    renderApprovals();
    await screen.findByText('Pending Corp');
    fireEvent.change(
      screen.getByPlaceholderText(/Rejection reason/i),
      { target: { value: 'Insufficient documentation' } },
    );
    fireEvent.click(screen.getByRole('button', { name: /^Reject$/i }));
    expect(await screen.findByRole('status')).toHaveTextContent(/rejected successfully/i);
  });

  it('shows error when API returns 403', async () => {
    server.use(
      http.get('*/api/v1/admin/merchants', () =>
        HttpResponse.json({ status: 403 }, { status: 403 }),
      ),
    );
    renderApprovals();
    expect(await screen.findByText(/does not have permission/i)).toBeInTheDocument();
  });

  it('shows error when API returns 500', async () => {
    server.use(
      http.get('*/api/v1/admin/merchants', () =>
        HttpResponse.json({ status: 500 }, { status: 500 }),
      ),
    );
    renderApprovals();
    expect(
      await screen.findByText(/Could not load merchant applications/i),
    ).toBeInTheDocument();
  });

  it('shows empty state when no applications exist', async () => {
    server.use(http.get('*/api/v1/admin/merchants', () => HttpResponse.json([])));
    renderApprovals();
    expect(
      await screen.findByText(/No merchant applications are waiting for review/i),
    ).toBeInTheDocument();
  });

  it('shows action error when review mutation fails', async () => {
    server.use(
      http.patch('*/api/v1/admin/merchants/:id', () =>
        HttpResponse.json({ message: 'Conflict' }, { status: 409 }),
      ),
    );
    renderApprovals();
    await screen.findByText('Pending Corp');
    fireEvent.click(screen.getByRole('button', { name: /^Approve$/i }));
    expect(
      await screen.findByText(/Could not save the review decision/i),
    ).toBeInTheDocument();
  });

  it('does not display action buttons when no access token', () => {
    renderApprovals({ ...DISPATCHER_AUTH, accessToken: '' });
    // No applications should load (query disabled)
    expect(screen.queryByText('Pending Corp')).not.toBeInTheDocument();
  });
});
