import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { AuthContext, type Auth } from '@/shared/auth/authContext';
import { CallbackPage } from './index';

describe('staff sign in handoff', () => {
  it('rejects a customer account entering through staff sign in', () => {
    const auth: Auth = { user: { id: 'customer', roles: ['CUSTOMER'], accessToken: '', returnTo: '/staff/login' }, setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    render(<MemoryRouter><AuthContext.Provider value={auth}><CallbackPage /></AuthContext.Provider></MemoryRouter>);
    expect(screen.getByRole('alert')).toHaveTextContent('This account is not a staff account');
    expect(auth.logout).toHaveBeenCalledWith('/staff/login?denied=1');
  });
});
