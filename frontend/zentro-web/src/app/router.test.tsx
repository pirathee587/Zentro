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

  it('renders the customer dashboard for an authenticated customer', async () => {
    server.use(http.get('*/api/v1/users/me', () => HttpResponse.json({ id: 'customer-id', name: 'Zentro Customer', roles: ['CUSTOMER'] })));
    getUser.mockResolvedValueOnce({
      expired: false,
      access_token: 'customer-token',
      profile: { sub: 'customer-id', realm_access: { roles: ['CUSTOMER'] } },
      state: { returnTo: '/customer', entryPoint: 'customer' },
    } as never);
    render(<Providers><MemoryRouter initialEntries={['/customer']}><AppRouter /></MemoryRouter></Providers>);
    expect(await screen.findByRole('heading', { name: /Welcome back/i }, { timeout: 10000 })).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /User profile options/i }));
    expect(screen.getByRole('menuitem', { name: /Sign out/i })).toBeInTheDocument();
  }, 15000);

  it('renders the courier portal for an authenticated courier', async () => {
    server.use(http.get('*/api/v1/users/me', () => HttpResponse.json({ id: 'courier-id', name: 'Zentro Courier', roles: ['COURIER'] })));
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
