import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthContext, type Auth, type EntryPoint } from '@/shared/auth/authContext';
import type { Role } from '@/shared/config/roles';
import { CallbackPage } from '../index';

function renderWithClient(ui: React.ReactElement) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={queryClient}>{ui}</QueryClientProvider>);
}

describe('staff sign in handoff', () => {
  it('rejects a customer account entering through staff sign in', () => {
    const auth: Auth = { user: { id: 'customer', roles: ['CUSTOMER'], accessToken: '', returnTo: '/staff/login' }, setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    renderWithClient(<MemoryRouter initialEntries={['/callback']}><AuthContext.Provider value={auth}><Routes><Route path="*" element={<CallbackPage />} /></Routes></AuthContext.Provider></MemoryRouter>);
    expect(screen.getByRole('heading', { name: 'This account is not enabled for staff.' })).toBeInTheDocument();
    expect(auth.logout).not.toHaveBeenCalled();
  });

  it('returns a dispatcher who started sign in on /dispatcher to the dispatcher portal', async () => {
    const auth: Auth = { user: { id: 'dispatcher', roles: ['DISPATCHER'], accessToken: 'test-token', returnTo: '/dispatcher', entryPoint: 'staff' }, setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    renderWithClient(<MemoryRouter initialEntries={['/callback']}><AuthContext.Provider value={auth}><Routes><Route path="*" element={<CallbackPage />} /><Route path="/dispatcher" element={<h1>Dispatcher portal opened</h1>} /></Routes></AuthContext.Provider></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Dispatcher portal opened' })).toBeInTheDocument();
    expect(screen.queryByText(/does not have access to a Zentro portal/i)).not.toBeInTheDocument();
  });
});

describe('landing after sign in', () => {
  const NO_PORTAL = /does not have access to a Zentro portal/i;
  const NOT_STAFF = 'This account is not enabled for staff.';
  const USE_STAFF = 'Use your staff sign-in.';

  // roles, entry point, requested path, expected landing (a path) or the message shown instead
  const cases: Array<[string, Role[], EntryPoint, string, string | RegExp]> = [
    ['dispatcher, staff sign in', ['DISPATCHER'], 'staff', '/staff/login', '/dispatcher'],
    ['courier, staff sign in', ['COURIER'], 'staff', '/staff/login', '/courier'],
    ['dispatcher who is also a courier', ['COURIER', 'DISPATCHER'], 'staff', '/courier', '/dispatcher'],
    ['merchant, staff sign in', ['MERCHANT'], 'staff', '/staff/login', NOT_STAFF],
    ['approved merchant, merchant sign in', ['MERCHANT'], 'merchant', '/merchant/dashboard', '/merchant/dashboard'],
    ['customer who is also a merchant, merchant sign in', ['CUSTOMER', 'MERCHANT'], 'merchant', '/merchant/dashboard', '/merchant/dashboard'],
    ['courier, merchant sign in', ['COURIER'], 'merchant', '/merchant/dashboard', USE_STAFF],
    ['dispatcher, merchant sign in', ['DISPATCHER'], 'merchant', '/merchant/dashboard', USE_STAFF],
    ['customer returning to a customer page', ['CUSTOMER'], 'customer', '/customer/history', '/customer/history'],
    ['customer from the home page', ['CUSTOMER'], 'customer', '/', '/customer'],
    ['customer whose return path points at the dispatcher portal', ['CUSTOMER'], 'customer', '/dispatcher/users', '/customer'],
    ['customer who is also a merchant, customer sign in', ['CUSTOMER', 'MERCHANT'], 'customer', '/customer', '/customer'],
    ['dispatcher, customer sign in', ['DISPATCHER'], 'customer', '/customer', NO_PORTAL],
    ['courier, customer sign in', ['COURIER'], 'customer', '/customer', NO_PORTAL],
    ['dispatcher who also holds CUSTOMER, customer sign in', ['CUSTOMER', 'DISPATCHER'], 'customer', '/customer', NO_PORTAL],
    ['token with no portal role (ADMIN or SERVICE only)', [], 'customer', '/customer', NO_PORTAL],
    ['token with no portal role, staff sign in', [], 'staff', '/staff/login', NOT_STAFF],
    // Finding F9: a merchant-only account using the customer sign in page is not sent to its dashboard.
    ['merchant-only account, customer sign in', ['MERCHANT'], 'customer', '/customer', NO_PORTAL],
  ];

  it.each(cases)('%s', async (_label, roles, entryPoint, returnTo, expected) => {
    const auth: Auth = { user: { id: 'user', roles, accessToken: 'test-token', returnTo, entryPoint, merchantStatus: roles.includes('MERCHANT') ? 'APPROVED' : undefined }, setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    renderWithClient(<MemoryRouter initialEntries={['/callback']}><AuthContext.Provider value={auth}><Routes>
      <Route path="*" element={<CallbackPage />} />
      {['/dispatcher', '/courier', '/merchant/dashboard', '/customer', '/customer/history'].map((path) =>
        <Route key={path} path={path} element={<h1>{`Landed on ${path}`}</h1>} />)}
    </Routes></AuthContext.Provider></MemoryRouter>);

    if (typeof expected === 'string' && expected.startsWith('/')) {
      expect(await screen.findByRole('heading', { name: `Landed on ${expected}` })).toBeInTheDocument();
      for (const other of ['/dispatcher', '/courier', '/merchant/dashboard', '/customer']) {
        if (other !== expected) expect(screen.queryByRole('heading', { name: `Landed on ${other}` })).not.toBeInTheDocument();
      }
    } else {
      expect(screen.getByText(expected)).toBeInTheDocument();
      expect(screen.queryByRole('heading', { name: /^Landed on/ })).not.toBeInTheDocument();
    }
    expect(auth.logout).not.toHaveBeenCalled();
  });
});

describe('merchant access handoff', () => {
  it('sends an account without MERCHANT role to merchant registration', async () => {
    const auth: Auth = { user: { id: 'customer', roles: ['CUSTOMER'], accessToken: 'test-token', returnTo: '/merchant' }, setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    renderWithClient(<MemoryRouter initialEntries={['/callback']}><AuthContext.Provider value={auth}><Routes><Route path="*" element={<CallbackPage />} /><Route path="/merchant/register-business" element={<h1>Merchant registration opened</h1>} /><Route path="/customer" element={<h1>Customer dashboard</h1>} /></Routes></AuthContext.Provider></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Merchant registration opened' })).toBeInTheDocument();
    expect(screen.queryByText('Merchant dashboard')).not.toBeInTheDocument();
  });

  it('sends a pending merchant applicant to the status route without showing the dashboard', async () => {
    const auth: Auth = { user: { id: 'customer', roles: ['CUSTOMER'], accessToken: 'test-token', returnTo: '/merchant/register-business', entryPoint: 'merchant', merchantStatus: 'PENDING' }, setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    renderWithClient(<MemoryRouter initialEntries={['/callback']}><AuthContext.Provider value={auth}><Routes><Route path="*" element={<CallbackPage />} /><Route path="/merchant/status" element={<h1>Merchant status opened</h1>} /></Routes></AuthContext.Provider></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Merchant status opened' })).toBeInTheDocument();
    expect(screen.queryByText('Merchant dashboard')).not.toBeInTheDocument();
  });

  it('prioritizes a merchant return path over stale customer entry point state', async () => {
    const auth: Auth = { user: { id: 'customer', roles: ['CUSTOMER'], accessToken: 'test-token', returnTo: '/merchant/dashboard', entryPoint: 'customer' }, setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    renderWithClient(<MemoryRouter initialEntries={['/callback']}><AuthContext.Provider value={auth}><Routes><Route path="*" element={<CallbackPage />} /><Route path="/merchant/register-business" element={<h1>Merchant registration opened</h1>} /><Route path="/customer" element={<h1>Customer dashboard</h1>} /></Routes></AuthContext.Provider></MemoryRouter>);

    expect(await screen.findByRole('heading', { name: 'Merchant registration opened' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Customer dashboard' })).not.toBeInTheDocument();
  });
});
