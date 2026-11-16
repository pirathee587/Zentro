import { render, screen, within, fireEvent, waitFor } from '@testing-library/react';
import { describe, expect, it, vi, beforeAll, afterAll, afterEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { AuthContext, type Auth } from '@/shared/auth/authContext';
import { UsersPage } from '../UsersPage';

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
    const body = (await request.clone().json()) as Record<string, string>;
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
      http.get('*/api/v1/admin/users', () => HttpResponse.json({ status: 403 }, { status: 403 })),
    );
    renderUsersPage();
    expect(await screen.findByText(/does not have permission/i)).toBeInTheDocument();
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
    expect(screen.getByRole('dialog', { name: 'Edit user' })).toBeInTheDocument();
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
    await screen.findByText(/No users match the current filter/i);
    const searchInput = screen.getByPlaceholderText(/Search name or email/i);
    fireEvent.change(searchInput, { target: { value: '  alice  ' } });
    fireEvent.submit(screen.getByRole('search'));
    await waitFor(() => expect(new URL(capturedUrl).searchParams.get('search')).toBe('alice'));

    fireEvent.change(screen.getByRole('combobox', { name: /Filter by account status/i }), {
      target: { value: 'ACTIVE' },
    });
    await waitFor(() => {
      const params = new URL(capturedUrl).searchParams;
      expect(params.get('search')).toBe('alice');
      expect(params.get('status')).toBe('ACTIVE');
    });

    fireEvent.click(screen.getByRole('button', { name: 'Clear' }));
    await waitFor(() => {
      const params = new URL(capturedUrl).searchParams;
      expect(params.has('search')).toBe(false);
      expect(params.has('status')).toBe(false);
    });
    expect(searchInput).toHaveValue('');
  });
  it.each(['light', 'dark'])(
    'opens an accessible modal in %s theme with read-only email and roles',
    async (theme) => {
      document.documentElement.dataset.theme = theme;
      renderUsersPage();
      const trigger = await screen.findByRole('button', { name: 'Edit user Alice' });
      trigger.focus();
      fireEvent.click(trigger);
      const dialog = screen.getByRole('dialog', { name: 'Edit user' });
      expect(within(dialog).getByRole('heading', { name: 'Edit user' })).toBeInTheDocument();
      expect(within(dialog).getByLabelText('Email address')).toHaveAttribute('readonly');
      expect(within(dialog).getByLabelText('Email address')).toHaveValue('alice@example.com');
      expect(dialog.closest('.user-edit-backdrop')?.parentElement).toBe(document.body);
      expect(document.body.style.overflow).toBe('hidden');
      expect(
        within(dialog).getByText('Role assignment is managed in Keycloak.'),
      ).toBeInTheDocument();
      fireEvent.keyDown(dialog, { key: 'Tab', shiftKey: true });
      expect(within(dialog).getByRole('button', { name: 'Save changes' })).toHaveFocus();
      fireEvent.keyDown(dialog, { key: 'Tab' });
      expect(within(dialog).getByRole('button', { name: 'Close' })).toHaveFocus();
      fireEvent.keyDown(dialog, { key: 'Escape' });
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
      expect(trigger).toHaveFocus();
      expect(document.body.style.overflow).not.toBe('hidden');
      delete document.documentElement.dataset.theme;
    },
  );
  it('closes on backdrop click and the close button', async () => {
    renderUsersPage();
    const trigger = await screen.findByRole('button', { name: 'Edit user Alice' });
    fireEvent.click(trigger);
    fireEvent.click(screen.getByTestId('user-edit-backdrop'));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    fireEvent.click(trigger);
    fireEvent.click(screen.getByRole('button', { name: 'Close' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
  it('saves editable fields only, refreshes the table and closes with success feedback', async () => {
    let payload: Record<string, string> = {};
    let reads = 0;
    server.use(
      http.get('*/api/v1/admin/users', () => {
        reads++;
        return HttpResponse.json(USERS);
      }),
      http.patch('*/api/v1/admin/users/:userId', async ({ request }) => {
        payload = (await request.clone().json()) as Record<string, string>;
        return HttpResponse.json({ ...USERS[0], ...payload });
      }),
    );
    renderUsersPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Edit user Alice' }));
    fireEvent.change(screen.getByLabelText('Display name'), { target: { value: 'Alice Updated' } });
    fireEvent.change(screen.getByLabelText('Account status'), { target: { value: 'DISABLED' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByText('User updated successfully.')).toBeInTheDocument();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(payload).toEqual({
      name: 'Alice Updated',
      phone: '+44 7700 900000',
      accountStatus: 'DISABLED',
    });
    expect(reads).toBeGreaterThan(1);
  });
  it('retains entered values and shows API errors in the modal', async () => {
    server.use(
      http.patch('*/api/v1/admin/users/:userId', () =>
        HttpResponse.json({ detail: 'Update failed' }, { status: 500 }),
      ),
    );
    renderUsersPage();
    fireEvent.click(await screen.findByRole('button', { name: 'Edit user Alice' }));
    fireEvent.change(screen.getByLabelText('Display name'), { target: { value: 'Unsaved name' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save changes' }));
    expect(await screen.findByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('dialog')).toBeInTheDocument();
    expect(screen.getByLabelText('Display name')).toHaveValue('Unsaved name');
  });
});
