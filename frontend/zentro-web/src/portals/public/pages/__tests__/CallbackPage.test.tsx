import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthContext, type Auth } from '@/shared/auth/authContext';
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
