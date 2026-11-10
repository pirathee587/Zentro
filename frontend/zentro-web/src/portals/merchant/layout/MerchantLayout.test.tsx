import { render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AuthContext, type Auth } from '@/shared/auth/authContext';
import type { Role } from '@/shared/config/roles';
import { MerchantLayout } from './MerchantLayout';

const auth = (roles: Role[]): Auth => ({
  user: { id: 'merchant', name: 'Manual Store', roles, accessToken: '', merchantStatus: 'APPROVED', profileLoaded: true },
  setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false,
});

const renderLayout = (roles: Role[]) => render(
  <MemoryRouter initialEntries={['/merchant/dashboard']}><AuthContext.Provider value={auth(roles)}>
    <Routes><Route element={<MerchantLayout />}><Route path="/merchant/dashboard" element={<p>Page</p>} /></Route></Routes>
  </AuthContext.Provider></MemoryRouter>,
);

describe('merchant navigation', () => {
  it.each<[string, Role[]]>([
    ['MERCHANT', ['MERCHANT']],
    ['CUSTOMER + MERCHANT', ['CUSTOMER', 'MERCHANT']],
  ])('shows only merchant dashboard links for a %s user', (_label, roles) => {
    renderLayout(roles);
    const nav = screen.getByRole('navigation', { name: 'Merchant navigation' });
    expect(within(nav).getAllByRole('link').map((link) => [link.textContent?.trim(), link.getAttribute('href')])).toEqual([
      ['Overview', '/merchant/dashboard'],
      ['API credentials', '/merchant/dashboard/api-credentials'],
      ['Webhooks', '/merchant/dashboard/webhooks'],
    ]);
    // In-page links such as "Skip to main content" (#main-content) are not navigation.
    for (const link of screen.getAllByRole('link').filter((item) => item.getAttribute('href')?.startsWith('/'))) {
      expect(link.getAttribute('href')).toMatch(/^\/merchant\/dashboard(\/|$)/);
    }
  });

  it('never links to customer, staff or administration pages', () => {
    renderLayout(['CUSTOMER', 'MERCHANT']);
    const hrefs = screen.getAllByRole('link').map((link) => link.getAttribute('href'));
    expect(hrefs.filter((href) => /^\/(customer|courier|dispatcher|admin|staff)(\/|$)/.test(href ?? ''))).toEqual([]);
    expect(screen.queryByRole('link', { name: /users|merchant applications|zones|integration/i })).not.toBeInTheDocument();
  });
});
