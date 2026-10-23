import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AuthContext, type Auth } from '@/shared/auth/authContext';
import { apiClient } from '@/shared/api/apiClient';
import { CallbackPage } from './index';

describe('staff sign in handoff', () => {
  it('rejects a customer account entering through staff sign in', () => {
    const auth: Auth = { user: { id: 'customer', roles: ['CUSTOMER'], accessToken: '', returnTo: '/staff/login' }, setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    render(<MemoryRouter><AuthContext.Provider value={auth}><CallbackPage /></AuthContext.Provider></MemoryRouter>);
    expect(screen.getByRole('heading', { name: 'This account is not enabled for staff.' })).toBeInTheDocument();
    expect(auth.logout).not.toHaveBeenCalled();
  });
});

describe('merchant access handoff', () => {
  it('keeps an account without MERCHANT role on the application status view', async () => {
    vi.spyOn(apiClient, 'get').mockRejectedValueOnce({ response: { status: 404 } });
    const auth: Auth = { user: { id: 'customer', roles: ['CUSTOMER'], accessToken: '', returnTo: '/merchant' }, setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    render(<MemoryRouter><AuthContext.Provider value={auth}><CallbackPage /></AuthContext.Provider></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: /let's get/i })).toBeInTheDocument();
    expect(screen.queryByText('Merchant dashboard')).not.toBeInTheDocument();
    vi.restoreAllMocks();
  });

  it('sends an existing merchant applicant to the status route without showing the dashboard', async () => {
    vi.spyOn(apiClient, 'get').mockResolvedValueOnce({ data: { status: 'PENDING' } });
    const auth: Auth = { user: { id: 'customer', roles: ['CUSTOMER'], accessToken: '', returnTo: '/merchant/register-business', entryPoint: 'merchant' }, setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    render(<MemoryRouter initialEntries={['/callback']}><AuthContext.Provider value={auth}><Routes><Route path="*" element={<CallbackPage />} /><Route path="/merchant/status" element={<h1>Merchant status opened</h1>} /></Routes></AuthContext.Provider></MemoryRouter>);
    expect(await screen.findByRole('heading', { name: 'Merchant status opened' })).toBeInTheDocument();
    expect(screen.queryByText('Merchant dashboard')).not.toBeInTheDocument();
    vi.restoreAllMocks();
  });
});
