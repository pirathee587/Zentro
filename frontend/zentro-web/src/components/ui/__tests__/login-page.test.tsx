import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthContext, type Auth } from '@/shared/auth/authContext';
import { ModernLoginPage } from '../login-page';

function renderWithClient(ui: React.ReactElement) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>);
}

describe('ModernLoginPage merchant sign-in redirection', () => {
  it('redirects an authenticated customer in merchant mode to /merchant/register-business if no application', async () => {
    const auth: Auth = {
      user: {
        id: 'cust-1',
        roles: ['CUSTOMER'],
        accessToken: 'test-token',
        merchantStatus: undefined,
        profileLoaded: true,
      },
      setUser: vi.fn(),
      login: vi.fn(async () => undefined),
      register: vi.fn(async () => undefined),
      logout: vi.fn(async () => undefined),
      loading: false,
    };

    renderWithClient(
      <MemoryRouter initialEntries={['/login?mode=merchant']}>
        <AuthContext.Provider value={auth}>
          <Routes>
            <Route path="/login" element={<ModernLoginPage />} />
            <Route path="/merchant/register-business" element={<h1>Business Registration Page</h1>} />
            <Route path="/merchant/status" element={<h1>Application Status Page</h1>} />
            <Route path="/merchant/dashboard" element={<h1>Merchant Dashboard</h1>} />
          </Routes>
        </AuthContext.Provider>
      </MemoryRouter>
    );

    expect(await screen.findByRole('heading', { name: 'Business Registration Page' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Merchant Dashboard' })).not.toBeInTheDocument();
  });

  it('redirects an authenticated applicant with PENDING status to /merchant/status', async () => {
    const auth: Auth = {
      user: {
        id: 'cust-1',
        roles: ['CUSTOMER'],
        accessToken: 'test-token',
        merchantStatus: 'PENDING',
        profileLoaded: true,
      },
      setUser: vi.fn(),
      login: vi.fn(async () => undefined),
      register: vi.fn(async () => undefined),
      logout: vi.fn(async () => undefined),
      loading: false,
    };

    renderWithClient(
      <MemoryRouter initialEntries={['/login?mode=merchant']}>
        <AuthContext.Provider value={auth}>
          <Routes>
            <Route path="/login" element={<ModernLoginPage />} />
            <Route path="/merchant/register-business" element={<h1>Business Registration Page</h1>} />
            <Route path="/merchant/status" element={<h1>Application Status Page</h1>} />
            <Route path="/merchant/dashboard" element={<h1>Merchant Dashboard</h1>} />
          </Routes>
        </AuthContext.Provider>
      </MemoryRouter>
    );

    expect(await screen.findByRole('heading', { name: 'Application Status Page' })).toBeInTheDocument();
  });

  it('redirects an approved merchant to /merchant/dashboard', async () => {
    const auth: Auth = {
      user: {
        id: 'merch-1',
        roles: ['MERCHANT'],
        accessToken: 'test-token',
        merchantStatus: 'APPROVED',
        profileLoaded: true,
      },
      setUser: vi.fn(),
      login: vi.fn(async () => undefined),
      register: vi.fn(async () => undefined),
      logout: vi.fn(async () => undefined),
      loading: false,
    };

    renderWithClient(
      <MemoryRouter initialEntries={['/login?mode=merchant']}>
        <AuthContext.Provider value={auth}>
          <Routes>
            <Route path="/login" element={<ModernLoginPage />} />
            <Route path="/merchant/register-business" element={<h1>Business Registration Page</h1>} />
            <Route path="/merchant/status" element={<h1>Application Status Page</h1>} />
            <Route path="/merchant/dashboard" element={<h1>Merchant Dashboard</h1>} />
          </Routes>
        </AuthContext.Provider>
      </MemoryRouter>
    );

    expect(await screen.findByRole('heading', { name: 'Merchant Dashboard' })).toBeInTheDocument();
  });

  it('ignores from: /merchant/login and redirects to /merchant/register-business for customer', async () => {
    const auth: Auth = {
      user: {
        id: 'cust-1',
        roles: ['CUSTOMER'],
        accessToken: 'test-token',
        merchantStatus: undefined,
        profileLoaded: true,
      },
      setUser: vi.fn(),
      login: vi.fn(async () => undefined),
      register: vi.fn(async () => undefined),
      logout: vi.fn(async () => undefined),
      loading: false,
    };

    renderWithClient(
      <MemoryRouter initialEntries={[{ pathname: '/login', search: '?mode=merchant', state: { from: '/merchant/login' } }]}>
        <AuthContext.Provider value={auth}>
          <Routes>
            <Route path="/login" element={<ModernLoginPage />} />
            <Route path="/merchant/login" element={<h1>Merchant Login Page</h1>} />
            <Route path="/merchant/register-business" element={<h1>Business Registration Page</h1>} />
          </Routes>
        </AuthContext.Provider>
      </MemoryRouter>
    );

    expect(await screen.findByRole('heading', { name: 'Business Registration Page' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Merchant Login Page' })).not.toBeInTheDocument();
  });

  it('calls loginWithCredentials with sanitized returnTo and navigates to targetDashboard', async () => {
    const loginWithCredentials = vi.fn().mockResolvedValue({
      success: true,
      roles: ['CUSTOMER'],
      targetDashboard: '/merchant/register-business',
    });

    const auth: Auth = {
      user: null,
      setUser: vi.fn(),
      login: vi.fn(async () => undefined),
      loginWithCredentials,
      register: vi.fn(async () => undefined),
      logout: vi.fn(async () => undefined),
      loading: false,
    };

    renderWithClient(
      <MemoryRouter initialEntries={[{ pathname: '/login', search: '?mode=merchant', state: { from: '/login?mode=merchant' } }]}>
        <AuthContext.Provider value={auth}>
          <Routes>
            <Route path="/login" element={<ModernLoginPage />} />
            <Route path="/merchant/register-business" element={<h1>Business Registration Page</h1>} />
          </Routes>
        </AuthContext.Provider>
      </MemoryRouter>
    );

    const emailInput = screen.getByPlaceholderText('you@example.com');
    const passwordInput = screen.getByPlaceholderText('Enter your password');
    const submitBtn = screen.getByRole('button', { name: /^sign in$/i });

    await userEvent.type(emailInput, 'merchant@zentro.lk');
    await userEvent.type(passwordInput, 'secret123');
    await userEvent.click(submitBtn);

    await waitFor(() => {
      expect(loginWithCredentials).toHaveBeenCalledWith('merchant@zentro.lk', 'secret123', undefined, 'merchant');
    });

    expect(await screen.findByRole('heading', { name: 'Business Registration Page' })).toBeInTheDocument();
  });
});
