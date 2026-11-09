import { render, screen, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AuthContext, type Auth } from '@/shared/auth/authContext';
import type { Role } from '@/shared/config/roles';
import { DispatcherLayout } from './DispatcherLayout';

const auth = (roles: Role[]): Auth => ({
  user: { id: 'dispatcher', name: 'Dispatch Desk', roles, accessToken: '' },
  setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false,
});

const renderLayout = (roles: Role[]) => render(
  <MemoryRouter initialEntries={['/dispatcher']}><AuthContext.Provider value={auth(roles)}>
    <Routes><Route element={<DispatcherLayout />}><Route path="/dispatcher" element={<p>Page</p>} /></Route></Routes>
  </AuthContext.Provider></MemoryRouter>,
);

describe('dispatcher navigation', () => {
  it.each<[string, Role[]]>([
    ['DISPATCHER', ['DISPATCHER']],
    ['DISPATCHER + COURIER', ['COURIER', 'DISPATCHER']],
  ])('shows the operational and administration links for a %s user', (_label, roles) => {
    renderLayout(roles);
    const nav = screen.getByRole('navigation', { name: 'Dispatcher navigation' });
    expect(within(nav).getAllByRole('link').map((link) => [link.textContent?.trim(), link.getAttribute('href')])).toEqual([
      ['Overview', '/dispatcher'],
      ['Dispatch Queue', '/dispatcher/queue'],
      ['Couriers', '/dispatcher/couriers'],
      ['Workload', '/dispatcher/workload'],
      ['Merchant applications', '/dispatcher/merchant-approvals'],
      ['Zones & pricing', '/dispatcher/zones-pricing'],
      ['Users', '/dispatcher/users'],
      ['Integration attempts', '/dispatcher/integration-attempts'],
    ]);
    // In-page links such as "Skip to main content" (#main-content) are not navigation.
    for (const link of screen.getAllByRole('link').filter((item) => item.getAttribute('href')?.startsWith('/'))) {
      expect(link.getAttribute('href')).toMatch(/^\/dispatcher(\/|$)/);
    }
  });

  it('keeps administration inside the Dispatcher portal: no Admin portal, customer or merchant links', () => {
    renderLayout(['DISPATCHER']);
    const hrefs = screen.getAllByRole('link').map((link) => link.getAttribute('href'));
    expect(hrefs.filter((href) => /^\/(admin|customer|merchant|courier|staff)(\/|$)/.test(href ?? ''))).toEqual([]);
    expect(screen.queryByText(/admin portal/i)).not.toBeInTheDocument();
  });
});
