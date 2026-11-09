import { render, screen, fireEvent } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '@/shared/testing/mocks/server';

const { getUser, signinSilent } = vi.hoisted(() => ({
  getUser: vi.fn().mockResolvedValue(null),
  signinSilent: vi.fn().mockRejectedValue(new Error('login_required')),
}));
vi.mock('@/shared/auth/oidcConfig', () => ({
  clearLegacyStoredTokens: vi.fn(),
  oidcManager: { getUser, signinSilent, events: { addUserLoaded: vi.fn(), removeUserLoaded: vi.fn(), addUserUnloaded: vi.fn(), removeUserUnloaded: vi.fn() } },
}));

import { Providers } from './providers';
import { AppRouter } from './router';

describe('portal routing', () => {
  it('renders a lazy-loaded public tracking route', async () => {
    render(<Providers><MemoryRouter initialEntries={['/track/ZTR-ABCDEFG234']}><AppRouter /></MemoryRouter></Providers>);
    expect(await screen.findByRole('heading', { name: /Track Your Shipment|Your parcel.s journey/i }, { timeout: 5000 })).toBeInTheDocument();
  });

  // Lazy-loading the portal bundle + OIDC restore + mock-adapter delay can exceed
  // vitest's default 5 s testTimeout; allow up to 40 s for this integration route test.
  it('renders the customer dashboard for an authenticated customer', async () => {
    server.use(http.get('*/api/v1/users/me', () => HttpResponse.json({ id: 'customer-id', name: 'Zentro Customer', roles: ['CUSTOMER'] })));
    getUser.mockResolvedValueOnce({
      expired: false,
      access_token: 'customer-token',
      profile: { sub: 'customer-id', realm_access: { roles: ['CUSTOMER'] } },
      state: { returnTo: '/customer', entryPoint: 'customer' },
    } as never);
    render(<Providers><MemoryRouter initialEntries={['/customer']}><AppRouter /></MemoryRouter></Providers>);
    expect(await screen.findByRole('heading', { name: /Welcome back/i }, { timeout: 15000 })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /User profile options/i }));
    expect(screen.getByRole('button', { name: /Sign out/i })).toBeInTheDocument();
  }, 40000);

  it('renders the courier portal for an authenticated courier', async () => {
    server.use(
      http.get('*/api/v1/users/me', () => HttpResponse.json({ id: 'courier-id', name: 'Zentro Courier', roles: ['COURIER'] })),
      http.get('*/api/v1/courier/deliveries', () => HttpResponse.json([])),
    );
    getUser.mockResolvedValueOnce({
      expired: false,
      access_token: 'courier-token',
      profile: { sub: 'courier-id', realm_access: { roles: ['COURIER'] } },
      state: { returnTo: '/courier', entryPoint: 'staff' },
    } as never);
    render(<Providers><MemoryRouter initialEntries={['/courier']}><AppRouter /></MemoryRouter></Providers>);
    expect(await screen.findByRole('heading', { name: /Welcome/i }, { timeout: 10000 })).toBeInTheDocument();
    expect(screen.getByRole('link', { name: /My profile/i })).toBeInTheDocument();
  });

  it('renders protected proof detail through the courier route', async () => {
    const id = '11111111-1111-4111-8111-111111111111';
    server.use(
      http.get('*/api/v1/users/me', () => HttpResponse.json({ id: 'courier-id', name: 'Courier', roles: ['COURIER'] })),
      http.get(`*/api/v1/courier/deliveries/${id}`, () => HttpResponse.json({ parcelId: id, courierId: 'profile', assignedAt: '2026-11-04T10:00:00Z', proofId: 'proof', completionState: 'PENDING' })),
      http.get(`*/api/v1/courier/deliveries/${id}/proof`, () => HttpResponse.json({ parcelId: id, proofId: 'proof', recipientName: 'Nimal', notes: null, proofReference: 'REF-1', submittedAt: '2026-11-04T11:00:00Z', completionState: 'PENDING', completedAt: null })),
    );
    getUser.mockResolvedValueOnce({ expired: false, access_token: 'courier-token',
      profile: { sub: 'courier-id', realm_access: { roles: ['COURIER'] } },
      state: { returnTo: `/courier/deliveries/${id}`, entryPoint: 'staff' } } as never);
    render(<Providers><MemoryRouter initialEntries={[`/courier/deliveries/${id}`]}><AppRouter /></MemoryRouter></Providers>);
    expect(await screen.findByRole('heading', { name: 'Saved proof' }, { timeout: 10000 })).toBeInTheDocument();
    expect(screen.getByText('Nimal')).toBeInTheDocument();
  });

  it('blocks customer access to the courier proof route', async () => {
    server.use(http.get('*/api/v1/users/me', () => HttpResponse.json({ id: 'customer-id', name: 'Customer', roles: ['CUSTOMER'] })));
    getUser.mockResolvedValueOnce({ expired: false, access_token: 'customer-token',
      profile: { sub: 'customer-id', realm_access: { roles: ['CUSTOMER'] } },
      state: { returnTo: '/courier/deliveries/11111111-1111-4111-8111-111111111111', entryPoint: 'staff' } } as never);
    render(<Providers><MemoryRouter initialEntries={['/courier/deliveries/11111111-1111-4111-8111-111111111111']}><AppRouter /></MemoryRouter></Providers>);
    expect(await screen.findByRole('heading', { name: 'Forbidden' }, { timeout: 10000 })).toBeInTheDocument();
    expect(screen.queryByText('Saved proof')).not.toBeInTheDocument();
  });

  it('renders courier management for an authenticated dispatcher', async () => {
    server.use(
      http.get('*/api/v1/users/me', () => HttpResponse.json({ id: 'dispatcher-id', name: 'Zentro Dispatcher', roles: ['DISPATCHER'] })),
      http.get('*/api/v1/admin/couriers', () => HttpResponse.json([]))
    );
    getUser.mockResolvedValueOnce({
      expired: false,
      access_token: 'dispatcher-token',
      profile: { sub: 'dispatcher-id', realm_access: { roles: ['DISPATCHER'] } },
      state: { returnTo: '/dispatcher/couriers', entryPoint: 'staff' },
    } as never);
    render(<Providers><MemoryRouter initialEntries={['/dispatcher/couriers']}><AppRouter /></MemoryRouter></Providers>);
    expect(await screen.findByRole('heading', { name: /Courier Management/i }, { timeout: 10000 })).toBeInTheDocument();
  });
});
