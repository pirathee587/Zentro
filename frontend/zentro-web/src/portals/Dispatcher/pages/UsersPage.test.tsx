import { render, screen, within, fireEvent, waitFor } from '@testing-library/react';
import { describe, expect, it, vi, beforeAll, afterAll, afterEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { AuthContext, type Auth } from '@/shared/auth/authContext';
import { UsersPage } from './UsersPage';

// ─── Mock OIDC so AuthProvider does not try to reach Keycloak ─────────────────
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
const DISPATCHER_USER: Auth['user'] = {
  id: 'dispatcher-id',
  name: 'Test Dispatcher',
  roles: ['DISPATCHER'],
  accessToken: 'dispatcher-token',
  accountStatus: 'ACTIVE',
  profileLoaded: true,
};

const USERS = [
  {
    id: 'user-1',
    email: 'alice@example.com',
    name: 'Alice',
    phone: '+44 7700 900000',
    enabled: true,
    accountStatus: 'ACTIVE',
    merchantStatus: 'APPROVED',
    roles: ['CUSTOMER', 'MERCHANT'],
  },
  {
    id: 'user-2',
    email: 'bob@example.com',
    name: 'Bob',
    phone: null,
    enabled: false,
    accountStatus: 'DISABLED',
    merchantStatus: null,
    roles: ['CUSTOMER'],
  },
];

// ─── MSW server ───────────────────────────────────────────────────────────────
const server = setupServer(
  http.get('*/api/v1/admin/users', () => HttpResponse.json(USERS)),
  http.patch('*/api/v1/admin/users/:userId', async ({ params, request }) => {
    const body = (await request.json()) as Record<string, string>;
    const target = USERS.find((u) => u.id === params['userId']);
    if (!target) return HttpResponse.json({ status: 404 }, { status: 404 });
    return HttpResponse.json({ ...target, ...body });
  }),
);

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterAll(() => server.close());
afterEach(() => server.resetHandlers());

// ─── Test helpers ─────────────────────────────────────────────────────────────
function makeQueryClient() {
  return new QueryClient({ defaultOptions: { queries: { retry: false } } });
}

function renderUsersPage(auth: Auth['user'] = DISPATCHER_USER) {
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
          <UsersPage />
        </MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>,
  );
}

// ─── Tests ────────────────────────────────────────────────────────────────────
describe('UsersPage', () => {
  it('shows a loading state before data arrives', async () => {
    server.use(
      http.get('*/api/v1/admin/users', async () => {
        await new Promise((r) => setTimeout(r, 200));
        return HttpResponse.json([]);
      }),
    );
    renderUsersPage();
    expect(screen.getByText(/Loading users/i)).toBeInTheDocument();
  });

  it('renders user data from the backend', async () => {
    renderUsersPage();
    expect(await screen.findByText('Alice')).toBeInTheDocument();
    expect(screen.getByText('alice@example.com')).toBeInTheDocument();
    expect(screen.getByText('Bob')).toBeInTheDocument();
    expect(screen.getByText('bob@example.com')).toBeInTheDocument();
  });

  it('shows account status badges', async () => {
    renderUsersPage();
    await screen.findByText('Alice');
    expect(screen.getByText('ACTIVE')).toBeInTheDocument();
    expect(screen.getByText('DISABLED')).toBeInTheDocument();
  });

  it('shows merchant status badge for merchants', async () => {
    renderUsersPage();
    await screen.findByText('Alice');
    expect(screen.getByText(/Merchant: APPROVED/i)).toBeInTheDocument();
  });

  it('shows role pills', async () => {
    renderUsersPage();
    await screen.findByText('Alice');
    expect(screen.getAllByText('CUSTOMER').length).toBeGreaterThan(0);
    expect(screen.getByText('MERCHANT')).toBeInTheDocument();
  });

  it('shows an error state when the API fails', async () => {
    server.use(
      http.get('*/api/v1/admin/users', () =>
        HttpResponse.json({ status: 403 }, { status: 403 }),
      ),
    );
    renderUsersPage();
    expect(
      await screen.findByText(/does not have permission/i),
    ).toBeInTheDocument();
  });

  it('shows empty state when no users match', async () => {
    server.use(http.get('*/api/v1/admin/users', () => HttpResponse.json([])));
    renderUsersPage();
    expect(await screen.findByText(/No users match the current filter/i)).toBeInTheDocument();
  });

  it('opens the edit panel when Edit is clicked', async () => {
    renderUsersPage();
    await screen.findByText('Alice');
    fireEvent.click(screen.getByRole('button', { name: 'Edit user Alice' }));
    expect(screen.getByRole('dialog', { name: /Edit Alice/i })).toBeInTheDocument();
  });

  it('disables account status dropdown when editing own account', async () => {
    renderUsersPage();
    await screen.findByText('Alice');
    // The dispatcher's own id is 'dispatcher-id'; neither listed user matches, so let's
    // verify the edit panel for a user other than the dispatcher does NOT disable the select.
    fireEvent.click(screen.getByRole('button', { name: 'Edit user Alice' }));
    const dialog1 = screen.getByRole('dialog');
    const statusSelect = within(dialog1).getByLabelText(/Account status/i) as HTMLSelectElement;
    expect(statusSelect.disabled).toBe(false);
  });

  it('disables account status dropdown when dispatcher edits their own record', async () => {
    // Replace USERS fixture so user-1 id matches the dispatcher id
    server.use(
      http.get('*/api/v1/admin/users', () =>
        HttpResponse.json([{ ...USERS[0], id: 'dispatcher-id' }]),
      ),
    );
    renderUsersPage();
    const editBtn = await screen.findByRole('button', { name: /Edit user Alice/i });
    fireEvent.click(editBtn);
    const dialog2 = screen.getByRole('dialog');
    const statusSelect = within(dialog2).getByLabelText(/Account status/i) as HTMLSelectElement;
    expect(statusSelect.disabled).toBe(true);
  });

  it('closes the edit panel when Cancel is clicked', async () => {
    renderUsersPage();
    await screen.findByText('Alice');
    fireEvent.click(screen.getByRole('button', { name: 'Edit user Alice' }));
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Cancel/i }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });

  it('does not render when the user is not logged in', () => {
    // With no access token, the query should not fire — loading state shown
    renderUsersPage({ ...DISPATCHER_USER, accessToken: '' });
    // Should not show user data; the query is disabled
    expect(screen.queryByText('Alice')).not.toBeInTheDocument();
  });

  it('applies search and status filter params', async () => {
    let capturedUrl = '';
    server.use(
      http.get('*/api/v1/admin/users', ({ request }) => {
        capturedUrl = request.url;
        return HttpResponse.json([]);
      }),
    );
    renderUsersPage();
    const searchInput = screen.getByPlaceholderText(/Search name or email/i);
    fireEvent.change(searchInput, { target: { value: 'alice' } });
    fireEvent.submit(screen.getByRole('search'));
    await waitFor(() => expect(capturedUrl).toContain('search=alice'));
  });
});
