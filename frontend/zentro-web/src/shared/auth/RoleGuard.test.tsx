import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AuthContext, type AccountStatus, type Auth, type MerchantStatus } from './authContext';
import { RoleGuard } from './RoleGuard';
import type { Role } from '@/shared/config/roles';

const guarded = (roles: Role[], allowed: Role[] = ['CUSTOMER'], accountStatus?: AccountStatus, merchantStatus?: MerchantStatus) => {
  const value: Auth = { user: { id: 'user', roles, accessToken: '', accountStatus, merchantStatus, profileLoaded: true }, setUser: () => undefined, login: async () => undefined, register: async () => undefined, logout: async () => undefined, loading: false };
  return <MemoryRouter initialEntries={['/secure']}><AuthContext.Provider value={value}><Routes><Route element={<RoleGuard roles={allowed} />}><Route path="/secure" element={<p>Allowed</p>} /></Route><Route path="/forbidden" element={<p>Denied</p>} /><Route path="/login" element={<p>Login</p>} /></Routes></AuthContext.Provider></MemoryRouter>;
};
describe('RoleGuard', () => {
  it('allows the matching customer role', () => { render(guarded(['CUSTOMER'])); expect(screen.getByText('Allowed')).toBeInTheDocument(); });
  it('denies a different authenticated role', () => { render(guarded(['COURIER'])); expect(screen.getByText('Denied')).toBeInTheDocument(); });
  it('does not treat a customer account as a merchant account', () => { render(guarded(['CUSTOMER'], ['MERCHANT'])); expect(screen.getByText('Denied')).toBeInTheDocument(); });
  it('denies disabled and suspended accounts', () => {
    const { unmount } = render(guarded(['CUSTOMER'], ['CUSTOMER'], 'DISABLED'));
    expect(screen.getByText('Denied')).toBeInTheDocument();
    unmount();
    render(guarded(['CUSTOMER'], ['CUSTOMER'], 'SUSPENDED'));
    expect(screen.getByText('Denied')).toBeInTheDocument();
  });
  it('denies a merchant with an explicit pending or rejected status', () => {
    const { unmount } = render(guarded(['MERCHANT'], ['MERCHANT'], 'ACTIVE', 'PENDING'));
    expect(screen.getByText('Denied')).toBeInTheDocument();
    unmount();
    render(guarded(['MERCHANT'], ['MERCHANT'], 'ACTIVE', 'REJECTED'));
    expect(screen.getByText('Denied')).toBeInTheDocument();
  });
  it('allows an active approved merchant', () => {
    render(guarded(['MERCHANT'], ['MERCHANT'], 'ACTIVE', 'APPROVED'));
    expect(screen.getByText('Allowed')).toBeInTheDocument();
  });
  it('denies merchant access when no approval record exists', () => {
    render(guarded(['MERCHANT'], ['MERCHANT'], 'ACTIVE'));
    expect(screen.getByText('Denied')).toBeInTheDocument();
  });

  describe('customer applicant on the merchant dashboard', () => {
    const atDashboard = (user: Auth['user'], path = '/merchant/dashboard') => {
      const value: Auth = { user, setUser: () => undefined, login: async () => undefined, register: async () => undefined, logout: async () => undefined, loading: false };
      return <MemoryRouter initialEntries={[path]}><AuthContext.Provider value={value}><Routes><Route element={<RoleGuard roles={['MERCHANT']} />}><Route path="/merchant/dashboard/*" element={<p>Dashboard</p>} /></Route><Route path="/forbidden" element={<p>Denied</p>} /></Routes></AuthContext.Provider></MemoryRouter>;
    };
    const applicant = (merchantStatus: MerchantStatus | undefined, profileLoaded: boolean): Auth['user'] =>
      ({ id: 'applicant', roles: ['CUSTOMER'], accessToken: '', accountStatus: profileLoaded ? 'ACTIVE' : undefined, merchantStatus, profileLoaded });

    it('waits for the profile instead of denying a reload of the dashboard', () => {
      render(atDashboard(applicant(undefined, false)));
      expect(screen.getByRole('status', { name: 'Checking merchant approval' })).toBeInTheDocument();
      expect(screen.queryByText('Denied')).not.toBeInTheDocument();
    });

    it('shows the pending dashboard once the profile reports PENDING', () => {
      const { rerender } = render(atDashboard(applicant(undefined, false)));
      rerender(atDashboard(applicant('PENDING', true)));
      expect(screen.getByText('Dashboard')).toBeInTheDocument();
    });

    it.each([['REJECTED'], [undefined]] as const)('denies the dashboard once the profile reports %s', (status) => {
      render(atDashboard(applicant(status, true)));
      expect(screen.getByText('Denied')).toBeInTheDocument();
    });

    it('does not wait on deeper merchant pages that a pending applicant cannot use', () => {
      render(atDashboard(applicant(undefined, false), '/merchant/dashboard/webhooks'));
      expect(screen.getByText('Denied')).toBeInTheDocument();
    });
  });
});
