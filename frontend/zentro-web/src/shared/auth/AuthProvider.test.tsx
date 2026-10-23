import { StrictMode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { signinCallback, signinRedirect, getUser, signinSilent, addUserLoaded, removeUserLoaded, addUserUnloaded, removeUserUnloaded } = vi.hoisted(() => ({
  signinCallback: vi.fn(), signinRedirect: vi.fn(), getUser: vi.fn(), signinSilent: vi.fn(), addUserLoaded: vi.fn(), removeUserLoaded: vi.fn(), addUserUnloaded: vi.fn(), removeUserUnloaded: vi.fn(),
}));

vi.mock('./oidcConfig', () => ({ clearLegacyStoredTokens: vi.fn(), oidcManager: {
  signinCallback, signinRedirect, getUser, signinSilent,
  events: { addUserLoaded, removeUserLoaded, addUserUnloaded, removeUserUnloaded },
} }));
vi.mock('@/shared/api/apiClient', () => ({
  setAccessToken: vi.fn(),
  apiClient: { get: vi.fn().mockResolvedValue({ data: { id: 'customer-id', name: 'Zentro Customer', roles: ['CUSTOMER'] } }), patch: vi.fn().mockResolvedValue({}) },
}));

import { AuthProvider } from './AuthProvider';
import { AuthContext } from './authContext';
import { Button } from '@/shared/ui';

describe('OIDC callback processing', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/callback?code=once&state=state');
    signinCallback.mockReset().mockResolvedValue({ expired: false, access_token: 'test-token', profile: { sub: 'customer-id', realm_access: { roles: ['CUSTOMER'] } }, state: { returnTo: '/customer', entryPoint: 'customer' } });
    signinRedirect.mockReset().mockResolvedValue(undefined);
    getUser.mockReset().mockResolvedValue(null);
    signinSilent.mockReset().mockRejectedValue(new Error('login_required'));
  });

  it('processes the authorization callback once in React StrictMode', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<StrictMode><QueryClientProvider client={client}><AuthProvider><p>Application ready</p></AuthProvider></QueryClientProvider></StrictMode>);
    expect(await screen.findByText('Application ready')).toBeInTheDocument();
    await waitFor(() => expect(signinCallback).toHaveBeenCalledTimes(1));
  });

  it('restores an existing Keycloak session with prompt none after reload', async () => {
    window.history.replaceState({}, '', '/');
    signinSilent.mockResolvedValue({ expired: false, access_token: 'restored-token', profile: { sub: 'customer-id', realm_access: { roles: ['CUSTOMER'] } }, state: undefined });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><AuthProvider><AuthContext.Consumer>{(auth) => <p>{auth?.user ? 'Session restored' : 'Signed out'}</p>}</AuthContext.Consumer></AuthProvider></QueryClientProvider>);
    expect(await screen.findByText('Session restored')).toBeInTheDocument();
    expect(signinSilent).toHaveBeenCalledWith({ extraQueryParams: { prompt: 'none' } });
  });

  it('sends an email hint to Keycloak and never includes a password', async () => {
    window.history.replaceState({}, '', '/');
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><AuthProvider><AuthContext.Consumer>{(auth) => <Button onClick={() => void auth?.login('/customer', 'person@example.com', 'customer')}>Continue</Button>}</AuthContext.Consumer></AuthProvider></QueryClientProvider>);
    fireEvent.click(await screen.findByRole('button', { name: 'Continue' }));
    await waitFor(() => expect(signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/customer', entryPoint: 'customer' }, extraQueryParams: { prompt: 'login', login_hint: 'person@example.com' } }));
  });
});
