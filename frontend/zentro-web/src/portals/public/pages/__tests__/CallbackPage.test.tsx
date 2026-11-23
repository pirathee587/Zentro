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

describe('unified role-based redirection', () => {
  it('redirects dispatcher to the dispatcher portal', async () => {
    const auth: Auth = { user: { id: 'dispatcher', roles: ['DISPATCHER'], accessToken: 'test-token', returnTo: '/dispatcher', entryPoint: 'staff' }, setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    renderWithClient(<MemoryRouter initialEntries={['/callback']}><AuthContext.Provider value={auth}><Routes><Route path="*" element={<CallbackPage />} /><Route path="/dispatcher" element={<h1>Dispatcher portal opened</h1>} /></Routes></AuthContext.Provider></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Dispatcher portal opened' })).toBeInTheDocument();
  });

  it('redirects courier to the courier portal from shared login', async () => {
    const auth: Auth = { user: { id: 'courier', roles: ['COURIER'], accessToken: 'test-token', returnTo: '/customer' }, setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    renderWithClient(<MemoryRouter initialEntries={['/callback']}><AuthContext.Provider value={auth}><Routes><Route path="*" element={<CallbackPage />} /><Route path="/courier" element={<h1>Courier portal opened</h1>} /></Routes></AuthContext.Provider></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Courier portal opened' })).toBeInTheDocument();
  });

  it('redirects merchant to the merchant dashboard from shared login', async () => {
    const auth: Auth = { user: { id: 'merchant', roles: ['MERCHANT'], accessToken: 'test-token', returnTo: '/customer' }, setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    renderWithClient(<MemoryRouter initialEntries={['/callback']}><AuthContext.Provider value={auth}><Routes><Route path="*" element={<CallbackPage />} /><Route path="/merchant/dashboard" element={<h1>Merchant dashboard opened</h1>} /></Routes></AuthContext.Provider></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Merchant dashboard opened' })).toBeInTheDocument();
  });

  it('redirects customer to the customer dashboard', async () => {
    const auth: Auth = { user: { id: 'customer', roles: ['CUSTOMER'], accessToken: 'test-token', returnTo: '/customer' }, setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    renderWithClient(<MemoryRouter initialEntries={['/callback']}><AuthContext.Provider value={auth}><Routes><Route path="*" element={<CallbackPage />} /><Route path="/customer" element={<h1>Customer dashboard opened</h1>} /></Routes></AuthContext.Provider></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Customer dashboard opened' })).toBeInTheDocument();
  });
});

describe('landing after sign in', () => {
  // roles, entry point, requested path, expected landing path
  const cases: Array<[string, Role[], EntryPoint, string, string]> = [
    ['dispatcher, shared sign in', ['DISPATCHER'], 'customer', '/login', '/dispatcher'],
    ['courier, shared sign in', ['COURIER'], 'customer', '/login', '/courier'],
    ['dispatcher who is also a courier', ['COURIER', 'DISPATCHER'], 'customer', '/courier', '/dispatcher'],
    ['dispatcher returning to a deep dispatcher page', ['DISPATCHER'], 'staff', '/dispatcher/parcels', '/dispatcher/parcels'],
    ['courier returning to a deep courier page', ['COURIER'], 'staff', '/courier/profile', '/courier/profile'],
    ['courier whose return path points at the dispatcher portal', ['COURIER'], 'staff', '/dispatcher/parcels', '/courier'],
    ['dispatcher with a protocol-relative return path', ['DISPATCHER'], 'staff', '//dispatcher.example', '/dispatcher'],
    ['approved merchant returning to a deep dashboard page', ['MERCHANT'], 'merchant', '/merchant/dashboard/webhooks', '/merchant/dashboard/webhooks'],
    ['merchant, shared sign in', ['MERCHANT'], 'customer', '/login', '/merchant/dashboard'],
    ['approved merchant, merchant return', ['MERCHANT'], 'merchant', '/merchant/dashboard', '/merchant/dashboard'],
    ['customer who is also a merchant', ['CUSTOMER', 'MERCHANT'], 'customer', '/merchant/dashboard', '/merchant/dashboard'],
    ['customer returning to a customer page', ['CUSTOMER'], 'customer', '/customer/history', '/customer/history'],
    ['customer from the home page', ['CUSTOMER'], 'customer', '/', '/customer'],
    ['customer whose return path points at the dispatcher portal', ['CUSTOMER'], 'customer', '/dispatcher/users', '/customer'],
    ['customer who is also a merchant, default customer return', ['CUSTOMER', 'MERCHANT'], 'customer', '/customer', '/customer'],
    ['token with no portal role defaults to customer', [], 'customer', '/customer', '/customer'],
  ];

  it.each(cases)('%s', async (_label, roles, entryPoint, returnTo, expected) => {
    const auth: Auth = { user: { id: 'user', roles, accessToken: 'test-token', returnTo, entryPoint, merchantStatus: roles.includes('MERCHANT') ? 'APPROVED' : undefined }, setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    renderWithClient(<MemoryRouter initialEntries={['/callback']}><AuthContext.Provider value={auth}><Routes>
      <Route path="*" element={<CallbackPage />} />
      {['/dispatcher', '/dispatcher/parcels', '/courier', '/courier/profile', '/merchant/dashboard', '/merchant/dashboard/webhooks', '/customer', '/customer/history'].map((path) =>
        <Route key={path} path={path} element={<h1>{`Landed on ${path}`}</h1>} />)}
    </Routes></AuthContext.Provider></MemoryRouter>);

    expect(await screen.findByRole('heading', { name: `Landed on ${expected}` })).toBeInTheDocument();
    for (const other of ['/dispatcher', '/courier', '/merchant/dashboard', '/customer']) {
      if (other !== expected) expect(screen.queryByRole('heading', { name: `Landed on ${other}` })).not.toBeInTheDocument();
    }
    expect(auth.logout).not.toHaveBeenCalled();
  });
});

describe('merchant access handoff', () => {
  it('sends an account without MERCHANT role to merchant registration', async () => {
    const auth: Auth = { user: { id: 'customer', roles: ['CUSTOMER'], accessToken: 'test-token', returnTo: '/merchant' }, setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    renderWithClient(<MemoryRouter initialEntries={['/callback']}><AuthContext.Provider value={auth}><Routes><Route path="*" element={<CallbackPage />} /><Route path="/merchant/register-business" element={<h1>Merchant registration opened</h1>} /><Route path="/customer" element={<h1>Customer dashboard</h1>} /></Routes></AuthContext.Provider></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Merchant registration opened' })).toBeInTheDocument();
    expect(screen.queryByText('Customer dashboard')).not.toBeInTheDocument();
  });

  it('sends a pending merchant applicant to the status route without showing the dashboard', async () => {
    const auth: Auth = { user: { id: 'customer', roles: ['CUSTOMER'], accessToken: 'test-token', returnTo: '/merchant/register-business', entryPoint: 'merchant', merchantStatus: 'PENDING' }, setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    renderWithClient(<MemoryRouter initialEntries={['/callback']}><AuthContext.Provider value={auth}><Routes><Route path="*" element={<CallbackPage />} /><Route path="/merchant/status" element={<h1>Merchant status opened</h1>} /></Routes></AuthContext.Provider></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Merchant status opened' })).toBeInTheDocument();
    expect(screen.queryByText('Merchant dashboard')).not.toBeInTheDocument();
  });

  it('sends a rejected merchant applicant to the status route', async () => {
    const auth: Auth = { user: { id: 'customer', roles: ['CUSTOMER'], accessToken: 'test-token', returnTo: '/merchant/register-business', entryPoint: 'merchant', merchantStatus: 'REJECTED' }, setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    renderWithClient(<MemoryRouter initialEntries={['/callback']}><AuthContext.Provider value={auth}><Routes><Route path="*" element={<CallbackPage />} /><Route path="/merchant/status" element={<h1>Merchant status opened</h1>} /></Routes></AuthContext.Provider></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Merchant status opened' })).toBeInTheDocument();
  });

  it('never redirects to /merchant/login when returnTo was an auth path', async () => {
    const auth: Auth = { user: { id: 'customer', roles: ['CUSTOMER'], accessToken: 'test-token', returnTo: '/merchant/login', entryPoint: 'merchant' }, setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    renderWithClient(<MemoryRouter initialEntries={['/callback']}><AuthContext.Provider value={auth}><Routes><Route path="*" element={<CallbackPage />} /><Route path="/merchant/register-business" element={<h1>Merchant registration opened</h1>} /><Route path="/login" element={<h1>Login page</h1>} /></Routes></AuthContext.Provider></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Merchant registration opened' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Login page' })).not.toBeInTheDocument();
  });

  it('prioritizes a merchant return path over stale customer entry point state', async () => {
    const auth: Auth = { user: { id: 'customer', roles: ['CUSTOMER'], accessToken: 'test-token', returnTo: '/merchant/dashboard', entryPoint: 'customer' }, setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    renderWithClient(<MemoryRouter initialEntries={['/callback']}><AuthContext.Provider value={auth}><Routes><Route path="*" element={<CallbackPage />} /><Route path="/merchant/register-business" element={<h1>Merchant registration opened</h1>} /><Route path="/customer" element={<h1>Customer dashboard</h1>} /></Routes></AuthContext.Provider></MemoryRouter>);

    expect(await screen.findByRole('heading', { name: 'Merchant registration opened' })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Customer dashboard' })).not.toBeInTheDocument();
  });
});
