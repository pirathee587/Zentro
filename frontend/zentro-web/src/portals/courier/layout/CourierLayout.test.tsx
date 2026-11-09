import { render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthContext, type Auth } from '@/shared/auth/authContext';
import type { Role } from '@/shared/config/roles';
import { CourierLayout } from './CourierLayout';

const auth = (roles: Role[]): Auth => ({
  user: { id: 'courier', name: 'Kamal Silva', roles, accessToken: '' },
  setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false,
});

const renderLayout = (roles: Role[]) => render(
  <QueryClientProvider client={new QueryClient()}><MemoryRouter initialEntries={['/courier']}><AuthContext.Provider value={auth(roles)}>
    <Routes><Route element={<CourierLayout />}><Route path="/courier" element={<p>Page</p>} /></Route></Routes>
  </AuthContext.Provider></MemoryRouter></QueryClientProvider>,
);

describe('courier navigation', () => {
  it.each<[string, Role[]]>([
    ['COURIER', ['COURIER']],
    ['COURIER + DISPATCHER', ['COURIER', 'DISPATCHER']],
  ])('shows only courier links for a %s user', (_label, roles) => {
    renderLayout(roles);
    const nav = screen.getByRole('navigation', { name: 'Courier navigation' });
    expect(within(nav).getAllByRole('link').map((link) => [link.textContent?.trim(), link.getAttribute('href')])).toEqual([
      ['My dashboard', '/courier'],
      ['My profile', '/courier/profile'],
    ]);
    // In-page links such as "Skip to main content" (#main-content) are not navigation.
    for (const link of screen.getAllByRole('link').filter((item) => item.getAttribute('href')?.startsWith('/'))) {
      expect(link.getAttribute('href')).toMatch(/^\/courier(\/|$)/);
    }
  });

  it('never links to customer, merchant or administration pages', () => {
    renderLayout(['COURIER', 'DISPATCHER']);
    const hrefs = screen.getAllByRole('link').map((link) => link.getAttribute('href'));
    expect(hrefs.filter((href) => /^\/(customer|merchant|dispatcher|admin)(\/|$)/.test(href ?? ''))).toEqual([]);
    expect(screen.queryByRole('link', { name: /users|merchant applications|zones|integration/i })).not.toBeInTheDocument();
  });
});
