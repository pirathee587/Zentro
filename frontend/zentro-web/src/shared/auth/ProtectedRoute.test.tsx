import { render, screen, waitFor } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { AuthContext, type Auth } from './authContext';
import { ProtectedRoute } from './ProtectedRoute';

describe('ProtectedRoute', () => {
  it('starts sign in with the requested path for a visitor', async () => {
    const login = vi.fn(async () => undefined);
    const auth: Auth = { user: null, setUser: vi.fn(), login, register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    render(<MemoryRouter initialEntries={['/secure']}><AuthContext.Provider value={auth}><Routes><Route element={<ProtectedRoute />}><Route path="/secure" element={<p>Private content</p>} /></Route></Routes></AuthContext.Provider></MemoryRouter>);
    expect(screen.getByText(/redirecting to sign in/i)).toBeInTheDocument();
    await waitFor(() => expect(login).toHaveBeenCalledWith('/secure', undefined, 'customer'));
    expect(screen.queryByText('Private content')).not.toBeInTheDocument();
  });
  it('renders private content for an authenticated user', () => {
    const auth: Auth = { user: { id: 'customer', roles: ['CUSTOMER'], accessToken: '' }, setUser: vi.fn(), login: vi.fn(async () => undefined), register: vi.fn(async () => undefined), logout: vi.fn(async () => undefined), loading: false };
    render(<MemoryRouter initialEntries={['/secure']}><AuthContext.Provider value={auth}><Routes><Route element={<ProtectedRoute />}><Route path="/secure" element={<p>Private content</p>} /></Route></Routes></AuthContext.Provider></MemoryRouter>);
    expect(screen.getByText('Private content')).toBeInTheDocument();
  });
});
