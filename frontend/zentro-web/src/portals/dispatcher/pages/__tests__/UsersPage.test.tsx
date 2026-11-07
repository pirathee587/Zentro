import { render, screen, within, fireEvent, waitFor } from '@testing-library/react';
import { describe, expect, it, vi, beforeAll, afterAll, afterEach } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { setupServer } from 'msw/node';
import { AuthContext, type Auth } from '@/shared/auth/authContext';
import { UsersPage, isValidSriLankanPhone } from '../UsersPage';

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
    phone: '+94 77 123 4567',
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
let lastPatchPayload: Record<string, unknown> | null = null;

const server = setupServer(
  http.get('*/api/v1/admin/users', () => HttpResponse.json(USERS)),
  http.patch('*/api/v1/admin/users/:userId', async ({ params, request }) => {
    const body = (await request.clone().json()) as Record<string, unknown>;
    lastPatchPayload = body;
    const target = USERS.find((u) => u.id === params['userId']);
    if (!target) return HttpResponse.json({ status: 404 }, { status: 404 });
    return HttpResponse.json({ ...target, ...body });
  }),
);

beforeAll(() => server.listen({ onUnhandledRequest: 'bypass' }));
afterAll(() => server.close());
afterEach(() => {
  server.resetHandlers();
  lastPatchPayload = null;
});

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
  describe('Initial rendering, search, and details', () => {
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
      expect(await screen.findByText(/does not have permission/i)).toBeInTheDocument();
    });

    it('shows empty state when no users match', async () => {
      server.use(http.get('*/api/v1/admin/users', () => HttpResponse.json([])));
      renderUsersPage();
      expect(await screen.findByText(/No users match the current filter/i)).toBeInTheDocument();
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
      fireEvent.change(searchInput, { target: { value: 'alice' } });
      fireEvent.submit(screen.getByRole('search'));
      await waitFor(() => expect(capturedUrl).toContain('search=alice'));
    });

    it('does not render when the user is not logged in', () => {
      renderUsersPage({ ...DISPATCHER_USER, accessToken: '' });
      expect(screen.queryByText('Alice')).not.toBeInTheDocument();
    });
  });

  describe('User edit panel and role governance', () => {
    it('opens the edit panel when Edit is clicked and displays user details', async () => {
      renderUsersPage();
      await screen.findByText('Alice');
      fireEvent.click(screen.getByRole('button', { name: 'Edit user Alice' }));
      const dialog = screen.getByRole('dialog', { name: /Edit Alice/i });
      expect(dialog).toBeInTheDocument();
      expect(within(dialog).getByText('user-1')).toBeInTheDocument();
      expect(within(dialog).getAllByText('alice@example.com').length).toBeGreaterThanOrEqual(1);
    });

    it('renders role badges read-only with Keycloak governance notice', async () => {
      renderUsersPage();
      await screen.findByText('Alice');
      fireEvent.click(screen.getByRole('button', { name: 'Edit user Alice' }));
      const dialog = screen.getByRole('dialog', { name: /Edit Alice/i });
      expect(within(dialog).getByText('Roles (Governed by Keycloak)')).toBeInTheDocument();
      expect(
        within(dialog).getByText(/Role assignment is managed centrally in Keycloak/i),
      ).toBeInTheDocument();
      // Ensure no input or select exists for role editing
      expect(within(dialog).queryByLabelText(/Roles/i)).not.toBeInTheDocument();
    });

    it('disables account status dropdown when dispatcher edits their own record', async () => {
      server.use(
        http.get('*/api/v1/admin/users', () =>
          HttpResponse.json([{ ...USERS[0], id: 'dispatcher-id' }]),
        ),
      );
      renderUsersPage();
      const editBtn = await screen.findByRole('button', { name: /Edit user Alice/i });
      fireEvent.click(editBtn);
      const dialog = screen.getByRole('dialog');
      const statusSelect = within(dialog).getByLabelText(/Account status/i) as HTMLSelectElement;
      expect(statusSelect.disabled).toBe(true);
      expect(
        within(dialog).getByText(/You cannot change your own account status/i),
      ).toBeInTheDocument();
    });

    it('closes the edit panel when Cancel is clicked', async () => {
      renderUsersPage();
      await screen.findByText('Alice');
      fireEvent.click(screen.getByRole('button', { name: 'Edit user Alice' }));
      expect(screen.getByRole('dialog')).toBeInTheDocument();
      fireEvent.click(screen.getByRole('button', { name: /Cancel/i }));
      expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    });
  });

  describe('Form validation', () => {
    it('validates Sri Lankan phone formats helper', () => {
      expect(isValidSriLankanPhone('+94771234567')).toBe(true);
      expect(isValidSriLankanPhone('+94 77 123 4567')).toBe(true);
      expect(isValidSriLankanPhone('0771234567')).toBe(true);
      expect(isValidSriLankanPhone('077-123-4567')).toBe(true);
      expect(isValidSriLankanPhone('12345')).toBe(false);
      expect(isValidSriLankanPhone('+447700900000')).toBe(false);
      expect(isValidSriLankanPhone('invalid')).toBe(false);
    });

    it('shows inline validation error when invalid phone is entered', async () => {
      renderUsersPage();
      await screen.findByText('Alice');
      fireEvent.click(screen.getByRole('button', { name: 'Edit user Alice' }));
      const dialog = screen.getByRole('dialog');
      const phoneInput = within(dialog).getByLabelText(/^Phone$/i);
      fireEvent.change(phoneInput, { target: { value: '12345' } });
      fireEvent.click(within(dialog).getByRole('button', { name: /Save changes/i }));
      expect(
        await within(dialog).findByText(/Enter a valid Sri Lankan phone number/i),
      ).toBeInTheDocument();
    });

    it('shows inline validation error when name is empty', async () => {
      renderUsersPage();
      await screen.findByText('Alice');
      fireEvent.click(screen.getByRole('button', { name: 'Edit user Alice' }));
      const dialog = screen.getByRole('dialog');
      const nameInput = within(dialog).getByLabelText(/Display name/i);
      fireEvent.change(nameInput, { target: { value: '   ' } });
      fireEvent.click(within(dialog).getByRole('button', { name: /Save changes/i }));
      expect(
        await within(dialog).findByText(/Name must not be empty/i),
      ).toBeInTheDocument();
    });

    it('updates name and phone directly without confirmation when status is unchanged', async () => {
      renderUsersPage();
      await screen.findByText('Alice');
      fireEvent.click(screen.getByRole('button', { name: 'Edit user Alice' }));
      const dialog = screen.getByRole('dialog');

      const nameInput = within(dialog).getByLabelText(/Display name/i);
      const phoneInput = within(dialog).getByLabelText(/^Phone$/i);
      const reasonInput = within(dialog).getByLabelText(/Reason for change/i);

      fireEvent.change(nameInput, { target: { value: 'Alice Modified' } });
      fireEvent.change(phoneInput, { target: { value: '+94 71 999 8888' } });
      fireEvent.change(reasonInput, { target: { value: 'Updated phone number' } });

      fireEvent.click(within(dialog).getByRole('button', { name: /Save changes/i }));

      // Status was not changed, so confirmation dialog should NOT open
      expect(screen.queryByText('Confirm Account Status Change')).not.toBeInTheDocument();

      await waitFor(() => {
        expect(lastPatchPayload).toEqual({
          name: 'Alice Modified',
          phone: '+94 71 999 8888',
          reason: 'Updated phone number',
          accountStatus: 'ACTIVE',
        });
      });
      // Role fields must never be included in the payload
      expect(lastPatchPayload).not.toHaveProperty('roles');
      expect(lastPatchPayload).not.toHaveProperty('role');
    });
  });

  describe('Status change confirmation dialog', () => {
    it('opens confirmation dialog when status is modified and allows canceling', async () => {
      renderUsersPage();
      await screen.findByText('Alice');
      fireEvent.click(screen.getByRole('button', { name: 'Edit user Alice' }));
      const dialog = screen.getByRole('dialog', { name: /Edit Alice/i });

      const statusSelect = within(dialog).getByLabelText(/Account status/i);
      fireEvent.change(statusSelect, { target: { value: 'DISABLED' } });

      const reasonInput = within(dialog).getByLabelText(/Reason for change/i);
      fireEvent.change(reasonInput, { target: { value: 'Suspected fraudulent activity' } });

      fireEvent.click(within(dialog).getByRole('button', { name: /Save changes/i }));

      // Confirmation modal should appear
      const confirmModal = await screen.findByRole('dialog', {
        name: /Confirm Account Status Change/i,
      });
      expect(confirmModal).toBeInTheDocument();
      expect(within(confirmModal).getByText(/Suspected fraudulent activity/i)).toBeInTheDocument();
      expect(
        within(confirmModal).getByText(/Warning: Changing account status to DISABLED/i),
      ).toBeInTheDocument();

      // Cancel button in confirmation modal closes confirmation dialog without submitting
      fireEvent.click(within(confirmModal).getByRole('button', { name: /Cancel/i }));
      expect(screen.queryByRole('dialog', { name: /Confirm Account Status Change/i })).not.toBeInTheDocument();
      expect(lastPatchPayload).toBeNull();
      // Edit dialog remains open
      expect(screen.getByRole('dialog', { name: /Edit Alice/i })).toBeInTheDocument();
    });

    it('confirms status change and submits with audit reason', async () => {
      renderUsersPage();
      await screen.findByText('Alice');
      fireEvent.click(screen.getByRole('button', { name: 'Edit user Alice' }));
      const dialog = screen.getByRole('dialog', { name: /Edit Alice/i });

      const statusSelect = within(dialog).getByLabelText(/Account status/i);
      fireEvent.change(statusSelect, { target: { value: 'SUSPENDED' } });

      const reasonInput = within(dialog).getByLabelText(/Reason for change/i);
      fireEvent.change(reasonInput, { target: { value: 'Compliance investigation' } });

      fireEvent.click(within(dialog).getByRole('button', { name: /Save changes/i }));

      const confirmModal = await screen.findByRole('dialog', {
        name: /Confirm Account Status Change/i,
      });
      fireEvent.click(within(confirmModal).getByRole('button', { name: /Confirm & apply/i }));

      await waitFor(() => {
        expect(lastPatchPayload).toEqual({
          name: 'Alice',
          phone: '+94 77 123 4567',
          accountStatus: 'SUSPENDED',
          reason: 'Compliance investigation',
        });
      });

      // Shows success banner
      expect(
        await screen.findByText(/Account status changed to SUSPENDED and recorded in audit log/i),
      ).toBeInTheDocument();
    });
  });

  describe('Server error handling and ProblemDetails', () => {
    it('displays server ProblemDetails fieldErrors under corresponding fields', async () => {
      server.use(
        http.patch('*/api/v1/admin/users/:userId', () =>
          HttpResponse.json(
            {
              type: 'about:blank',
              title: 'Bad Request',
              status: 400,
              code: 'VALIDATION_FAILED',
              detail: 'Validation failed for submitted fields.',
              fieldErrors: {
                phone: 'Phone must be a valid Sri Lankan number.',
                name: 'Name contains invalid characters.',
              },
            },
            { status: 400 },
          ),
        ),
      );

      renderUsersPage();
      await screen.findByText('Alice');
      fireEvent.click(screen.getByRole('button', { name: 'Edit user Alice' }));
      const dialog = screen.getByRole('dialog');

      fireEvent.click(within(dialog).getByRole('button', { name: /Save changes/i }));

      expect(
        await within(dialog).findByText('Phone must be a valid Sri Lankan number.'),
      ).toBeInTheDocument();
      expect(
        within(dialog).getByText('Name contains invalid characters.'),
      ).toBeInTheDocument();
    });

    it('displays server SELF_STATUS_CHANGE error', async () => {
      server.use(
        http.patch('*/api/v1/admin/users/:userId', () =>
          HttpResponse.json(
            {
              title: 'Bad Request',
              status: 400,
              code: 'SELF_STATUS_CHANGE',
              detail: 'A dispatcher cannot change their own account status.',
            },
            { status: 400 },
          ),
        ),
      );

      renderUsersPage();
      await screen.findByText('Alice');
      fireEvent.click(screen.getByRole('button', { name: 'Edit user Alice' }));
      const dialog = screen.getByRole('dialog');

      fireEvent.click(within(dialog).getByRole('button', { name: /Save changes/i }));

      expect(
        await within(dialog).findByText('You cannot change your own account status.'),
      ).toBeInTheDocument();
    });

    it('displays server PROTECTED_FIELD error if returned', async () => {
      server.use(
        http.patch('*/api/v1/admin/users/:userId', () =>
          HttpResponse.json(
            {
              title: 'Bad Request',
              status: 400,
              code: 'PROTECTED_FIELD',
              detail: "Field 'roles' cannot be changed through this API.",
            },
            { status: 400 },
          ),
        ),
      );

      renderUsersPage();
      await screen.findByText('Alice');
      fireEvent.click(screen.getByRole('button', { name: 'Edit user Alice' }));
      const dialog = screen.getByRole('dialog');

      fireEvent.click(within(dialog).getByRole('button', { name: /Save changes/i }));

      expect(
        await within(dialog).findByText("Field 'roles' cannot be changed through this API."),
      ).toBeInTheDocument();
    });

    it('handles network failure gracefully without crashing', async () => {
      server.use(
        http.patch('*/api/v1/admin/users/:userId', () => HttpResponse.error()),
      );

      renderUsersPage();
      await screen.findByText('Alice');
      fireEvent.click(screen.getByRole('button', { name: 'Edit user Alice' }));
      const dialog = screen.getByRole('dialog');

      fireEvent.click(within(dialog).getByRole('button', { name: /Save changes/i }));

      expect(
        await within(dialog).findByText(/We could not reach Zentro|Could not update the user/i),
      ).toBeInTheDocument();
    });
  });
});
