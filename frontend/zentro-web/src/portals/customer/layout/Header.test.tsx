import { render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { AuthContext, type Auth } from '@/shared/auth/authContext';
import type { Role } from '@/shared/config/roles';
import { Header } from './Header';

const auth = (roles: Role[]): Auth => ({
  user: { id: 'user', name: 'Test User', roles, accessToken: '' },
  setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false,
});

const renderHeader = (roles: Role[]) => render(
  <MemoryRouter initialEntries={['/customer']}><AuthContext.Provider value={auth(roles)}><Header /></AuthContext.Provider></MemoryRouter>,
);

describe('customer navigation', () => {
  it.each<[string, Role[]]>([
    ['CUSTOMER', ['CUSTOMER']],
    ['CUSTOMER + MERCHANT', ['CUSTOMER', 'MERCHANT']],
  ])('shows only customer links for a %s user', (_label, roles) => {
    renderHeader(roles);
    const nav = screen.getByRole('navigation', { name: 'Customer Navigation' });
    expect(within(nav).getAllByRole('link').map((link) => [link.textContent?.trim(), link.getAttribute('href')])).toEqual([
      ['Dashboard', '/customer'],
      ['Send parcel', '/customer/send'],
      ['Track', '/customer/track'],
      ['History', '/customer/history'],
      ['Receipts', '/customer/receipts'],
      ['Support', '/customer/support'],
    ]);
    // In-page links such as "Skip to main content" (#main-content) are not navigation.
    for (const link of screen.getAllByRole('link').filter((item) => item.getAttribute('href')?.startsWith('/'))) {
      expect(link.getAttribute('href')).toMatch(/^\/customer(\/|$)/);
    }
  });

  it('never links to merchant, staff or administration pages', () => {
    renderHeader(['CUSTOMER', 'MERCHANT']);
    const hrefs = screen.getAllByRole('link').map((link) => link.getAttribute('href'));
    expect(hrefs.filter((href) => /^\/(merchant|courier|dispatcher|admin|staff)(\/|$)/.test(href ?? ''))).toEqual([]);
    expect(screen.queryByRole('link', { name: /users|merchant applications|zones|integration/i })).not.toBeInTheDocument();
  });
});
