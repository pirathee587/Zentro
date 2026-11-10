import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { MemoryRouter, useRoutes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthContext, type Auth } from '@/shared/auth/authContext';
import type { Role } from '@/shared/config/roles';
import { portalRoutes } from '../routes';

function PublicPortal() {
  return useRoutes(portalRoutes);
}

const auth = (roles: Role[] | null): Auth => ({
  user: roles ? { id: 'user', roles, accessToken: '' } : null,
  setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false,
});

const renderHome = (roles: Role[] | null) => render(
  <QueryClientProvider client={new QueryClient()}><MemoryRouter initialEntries={['/']}><AuthContext.Provider value={auth(roles)}>
    <PublicPortal />
  </AuthContext.Provider></MemoryRouter></QueryClientProvider>,
);

describe('public header', () => {
  it.each<[string, Role[] | null]>([
    ['a visitor', null],
    ['a CUSTOMER', ['CUSTOMER']],
    ['a MERCHANT', ['MERCHANT']],
    ['a COURIER', ['COURIER']],
    ['a DISPATCHER', ['DISPATCHER']],
  ])('is the same for %s and links to no portal', (_label, roles) => {
    renderHome(roles);
    expect(screen.getByRole('link', { name: 'Login' })).toHaveAttribute('href', '/login');
    expect(screen.getByRole('link', { name: /Get Started/ })).toHaveAttribute('href', '/register');
    const hrefs = screen.getAllByRole('link').map((link) => link.getAttribute('href') ?? '');
    expect(hrefs.filter((href) => /^\/(customer|merchant\/dashboard|courier|dispatcher|admin)(\/|$)/.test(href))).toEqual([]);
  });
});
