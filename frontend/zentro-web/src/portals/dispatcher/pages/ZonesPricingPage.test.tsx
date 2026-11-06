import { describe, expect, it, vi } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/shared/testing/mocks/server';

const { getUser, signinSilent } = vi.hoisted(() => ({
  getUser: vi.fn().mockResolvedValue(null),
  signinSilent: vi.fn().mockRejectedValue(new Error('login_required')),
}));
vi.mock('@/shared/auth/oidcConfig', () => ({
  clearLegacyStoredTokens: vi.fn(),
  oidcManager: {
    getUser,
    signinSilent,
    events: { addUserLoaded: vi.fn(), removeUserLoaded: vi.fn(), addUserUnloaded: vi.fn(), removeUserUnloaded: vi.fn() },
  },
}));

import { Providers } from '@/app/providers';
import { AppRouter } from '@/app/router';
import { ZonesPricingPage } from './ZonesPricingPage';

const RULE = {
  id: 'rule-1', ruleName: 'STANDARD', baseFee: 550, includedWeightKg: 1, additionalWeightRatePerKg: 120,
  distanceSurchargeOtherDistrict: 402, expressSurcharge: 300, perKmRate: 0, currency: 'LKR', active: true,
  updatedAt: '2026-11-05T10:00:00Z',
};
const ZONES = [{ id: 'z1', code: 'ZONE_CENTRAL', name: 'Central', description: 'Kandy area', active: true }];

function livePricing() {
  server.use(
    http.get('*/api/v1/admin/pricing-rules', () => HttpResponse.json(RULE)),
    http.get('*/api/v1/zones', () => HttpResponse.json(ZONES)),
  );
}

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(
    <QueryClientProvider client={client}><MemoryRouter><ZonesPricingPage /></MemoryRouter></QueryClientProvider>,
  );
}

/** Signs in a user with these realm roles and opens a path through the real app router and guards. */
function openAs(roles: string[], path: string) {
  server.use(http.get('*/api/v1/users/me', () =>
    HttpResponse.json({ id: 'user-id', name: 'Zentro User', roles, accountStatus: 'ACTIVE', merchantStatus: 'APPROVED' })));
  getUser.mockResolvedValueOnce({
    expired: false,
    access_token: 'test-token',
    profile: { sub: 'user-id', realm_access: { roles } },
    state: { returnTo: path, entryPoint: 'staff' },
  } as never);
  render(<Providers><MemoryRouter initialEntries={[path]}><AppRouter /></MemoryRouter></Providers>);
}

describe('ZonesPricingPage', () => {
  it('shows the live pricing rule, the quotation preview and the zones', async () => {
    livePricing();
    renderPage();

    expect(screen.getByRole('heading', { name: 'Zones & pricing', level: 1 })).toBeInTheDocument();
    expect(await screen.findByLabelText('Base fee (LKR)')).toHaveValue('550.00');
    expect(screen.getByRole('form', { name: 'Quotation preview' })).toBeInTheDocument();
    const zones = screen.getByRole('region', { name: 'Delivery zones' });
    expect(await within(zones).findByText('ZONE_CENTRAL')).toBeInTheDocument();
    expect(screen.queryByText('Zone and pricing tools are not connected yet.')).not.toBeInTheDocument();
  });

  it('shows the permission message in each section when the API answers 403', async () => {
    server.use(
      http.get('*/api/v1/admin/pricing-rules', () => new HttpResponse(null, { status: 403 })),
      http.get('*/api/v1/zones', () => new HttpResponse(null, { status: 403 })),
    );
    renderPage();

    const rule = screen.getByRole('region', { name: 'Pricing rule' });
    const zones = screen.getByRole('region', { name: 'Delivery zones' });
    expect(await within(rule).findByRole('alert')).toHaveTextContent('Your account does not have permission to do that.');
    expect(await within(zones).findByRole('alert')).toHaveTextContent('Your account does not have permission to do that.');
    expect(screen.queryByLabelText('Base fee (LKR)')).not.toBeInTheDocument();
  });

  it('a dispatcher reaches the page through the navigation link', async () => {
    livePricing();
    openAs(['DISPATCHER'], '/dispatcher/zones-pricing');

    expect(await screen.findByRole('heading', { name: 'Zones & pricing', level: 1 }, { timeout: 10000 })).toBeInTheDocument();
    const nav = screen.getByRole('navigation', { name: 'Dispatcher navigation' });
    const link = within(nav).getByRole('link', { name: 'Zones & pricing' });
    expect(link).toHaveAttribute('href', '/dispatcher/zones-pricing');
    expect(link).toHaveClass('active');
    expect(await screen.findByLabelText('Base fee (LKR)')).toHaveValue('550.00');
  });

  it.each([['CUSTOMER'], ['MERCHANT'], ['COURIER']])('a %s is sent to Forbidden by the route guard', async (role) => {
    livePricing();
    openAs([role], '/dispatcher/zones-pricing');

    expect(await screen.findByRole('heading', { name: 'Forbidden' }, { timeout: 10000 })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: 'Zones & pricing' })).not.toBeInTheDocument();
    expect(screen.queryByLabelText('Base fee (LKR)')).not.toBeInTheDocument();
  });
});
