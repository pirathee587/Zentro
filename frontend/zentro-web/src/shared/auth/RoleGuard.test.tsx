import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AuthContext, type Auth } from './authContext';
import { RoleGuard } from './RoleGuard';
import type { Role } from '@/shared/config/roles';

const guarded = (roles: Role[], allowed: Role[] = ['CUSTOMER']) => {
  const value: Auth = { user: { id: 'user', roles, accessToken: '' }, setUser: () => undefined, login: async () => undefined, register: async () => undefined, logout: async () => undefined, loading: false };
  return <MemoryRouter initialEntries={['/secure']}><AuthContext.Provider value={value}><Routes><Route element={<RoleGuard roles={allowed} />}><Route path="/secure" element={<p>Allowed</p>} /></Route><Route path="/forbidden" element={<p>Denied</p>} /><Route path="/login" element={<p>Login</p>} /></Routes></AuthContext.Provider></MemoryRouter>;
};
describe('RoleGuard', () => {
  it('allows the matching customer role', () => { render(guarded(['CUSTOMER'])); expect(screen.getByText('Allowed')).toBeInTheDocument(); });
  it('denies a different authenticated role', () => { render(guarded(['COURIER'])); expect(screen.getByText('Denied')).toBeInTheDocument(); });
  it('does not treat a customer account as a merchant account', () => { render(guarded(['CUSTOMER'], ['MERCHANT'])); expect(screen.getByText('Denied')).toBeInTheDocument(); });
});
