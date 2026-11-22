import { StrictMode } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { fireEvent, render, screen, waitFor } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

const { signinCallback, signinRedirect, signoutRedirect, getUser, removeUser, signinSilent, addUserLoaded, removeUserLoaded, addUserUnloaded, removeUserUnloaded } = vi.hoisted(() => ({
  signinCallback: vi.fn(), signinRedirect: vi.fn(), signoutRedirect: vi.fn(), getUser: vi.fn(), removeUser: vi.fn(), signinSilent: vi.fn(), addUserLoaded: vi.fn(), removeUserLoaded: vi.fn(), addUserUnloaded: vi.fn(), removeUserUnloaded: vi.fn(),
}));

vi.mock('./oidcConfig', () => ({ clearLegacyStoredTokens: vi.fn(), oidcManager: {
  signinCallback, signinRedirect, signoutRedirect, getUser, removeUser, signinSilent,
  events: { addUserLoaded, removeUserLoaded, addUserUnloaded, removeUserUnloaded },
} }));
vi.mock('@/shared/api/apiClient', () => ({
  setAccessToken: vi.fn(),
  withBearerToken: (_token: string, config: object = {}) => config,
  apiClient: { get: vi.fn().mockResolvedValue({ data: { id: 'customer-id', name: 'Zentro Customer', roles: ['CUSTOMER'] } }), patch: vi.fn().mockResolvedValue({}) },
}));

import { AuthProvider, resetSigninRedirectGuardForTests } from './AuthProvider';
import { AuthContext } from './authContext';
import { Button } from '@/shared/ui';
import { queryClient } from '@/shared/api/queryClient';
import { apiClient } from '@/shared/api/apiClient';

describe('OIDC callback processing', () => {
  beforeEach(() => {
    window.history.replaceState({}, '', '/callback?code=once&state=state');
    window.sessionStorage.clear();
    signinCallback.mockReset().mockResolvedValue({ expired: false, access_token: 'test-token', profile: { sub: 'customer-id', realm_access: { roles: ['CUSTOMER'] } }, state: { returnTo: '/customer', entryPoint: 'customer' } });
    signinRedirect.mockReset().mockResolvedValue(undefined);
    signoutRedirect.mockReset().mockResolvedValue(undefined);
    getUser.mockReset().mockResolvedValue(null);
    removeUser.mockReset().mockResolvedValue(null);
    signinSilent.mockReset().mockRejectedValue(new Error('login_required'));
    window.sessionStorage.removeItem('zentro.sso-restore-suppressed');
    resetSigninRedirectGuardForTests();
  });

  it('signs out completely and explains when S1 refuses a second identity for the same email', async () => {
    window.history.replaceState({}, '', '/customer');
    signinSilent.mockResolvedValue({ expired: false, access_token: 'google-token', profile: { sub: 'new-google-subject', realm_access: { roles: ['CUSTOMER'] } }, state: undefined });
    const conflict = Object.assign(new Error('conflict'), { isAxiosError: true, response: { status: 409, data: { code: 'ACCOUNT_IDENTITY_CONFLICT' } } });
    vi.mocked(apiClient.get).mockRejectedValueOnce(conflict);
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><AuthProvider><p>App</p></AuthProvider></QueryClientProvider>);
    await waitFor(() => expect(signoutRedirect).toHaveBeenCalledWith({ post_logout_redirect_uri: `${window.location.origin}/login?identity_conflict=1&zentro_signed_out=1` }));
    expect(await screen.findByText(/already registered with a different Zentro sign-in/)).toBeInTheDocument();
  });

  it('shows the identity-conflict explanation after the sign-out redirect', async () => {
    window.history.replaceState({}, '', '/login?identity_conflict=1&zentro_signed_out=1');
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><AuthProvider><p>Sign in page</p></AuthProvider></QueryClientProvider>);
    expect(await screen.findByText(/already registered with a different Zentro sign-in/)).toBeInTheDocument();
    expect(signinSilent).not.toHaveBeenCalled();
  });

  it('reuses a valid Keycloak session for automatic protected-route redirects', async () => {
    window.history.replaceState({}, '', '/');
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><AuthProvider><AuthContext.Consumer>{(auth) => <Button onClick={() => void auth?.login('/dispatcher/parcels', undefined, 'staff', undefined, { reauthenticate: false })}>Guard</Button>}</AuthContext.Consumer></AuthProvider></QueryClientProvider>);
    fireEvent.click(await screen.findByRole('button', { name: 'Guard' }));
    await waitFor(() => expect(signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/dispatcher/parcels', entryPoint: 'staff' }, extraQueryParams: {} }));
  });

  it('still forces credentials for an automatic redirect after an explicit sign-out', async () => {
    window.history.replaceState({}, '', '/');
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><AuthProvider><AuthContext.Consumer>{(auth) => <Button onClick={() => void auth?.login('/courier', undefined, 'staff', undefined, { reauthenticate: false })}>Guard</Button>}</AuthContext.Consumer></AuthProvider></QueryClientProvider>);
    const guard = await screen.findByRole('button', { name: 'Guard' });
    window.sessionStorage.setItem('zentro.sso-restore-suppressed', '1');
    fireEvent.click(guard);
    await waitFor(() => expect(signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/courier', entryPoint: 'staff' }, extraQueryParams: { prompt: 'login' } }));
  });

  it('starts only one authorization redirect when sign-in is triggered repeatedly', async () => {
    window.history.replaceState({}, '', '/');
    signinRedirect.mockReturnValue(new Promise(() => {}));
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><AuthProvider><AuthContext.Consumer>{(auth) => <Button onClick={() => { void auth?.login('/customer'); void auth?.login('/customer'); }}>Twice</Button>}</AuthContext.Consumer></AuthProvider></QueryClientProvider>);
    fireEvent.click(await screen.findByRole('button', { name: 'Twice' }));
    await waitFor(() => expect(signinRedirect).toHaveBeenCalledTimes(1));
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

  it('does not silently restore a Keycloak SSO session after an explicit account switch', async () => {
    window.history.replaceState({}, '', '/login?switch_account=1');
    getUser.mockResolvedValue({ expired: false, access_token: 'old-account-token', profile: { sub: 'old-account' } });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><AuthProvider><AuthContext.Consumer>{(auth) => <p>{auth?.user ? 'Signed in' : 'Signed out'}</p>}</AuthContext.Consumer></AuthProvider></QueryClientProvider>);
    expect(await screen.findByText('Signed out')).toBeInTheDocument();
    expect(removeUser).toHaveBeenCalledTimes(1);
    expect(getUser).not.toHaveBeenCalled();
    expect(signinSilent).not.toHaveBeenCalled();
  });

  it('clears local user data and uses a same-origin Keycloak logout return', async () => {
    window.history.replaceState({}, '', '/customer');
    const clear = vi.spyOn(queryClient, 'clear');
    const cancel = vi.spyOn(queryClient, 'cancelQueries').mockResolvedValue();
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><AuthProvider><AuthContext.Consumer>{(auth) => <Button onClick={() => void auth?.logout('https://example.invalid/after-logout')}>Switch</Button>}</AuthContext.Consumer></AuthProvider></QueryClientProvider>);
    fireEvent.click(await screen.findByRole('button', { name: 'Switch' }));
    await waitFor(() => expect(signoutRedirect).toHaveBeenCalledWith({ post_logout_redirect_uri: `${window.location.origin}/?zentro_signed_out=1` }));
    expect(cancel).toHaveBeenCalled();
    expect(clear).toHaveBeenCalled();
    expect(window.sessionStorage.getItem('zentro.sso-restore-suppressed')).toBe('1');
    clear.mockRestore();
    cancel.mockRestore();
  });

  it('sends an email hint to Keycloak and never includes a password', async () => {
    window.history.replaceState({}, '', '/');
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><AuthProvider><AuthContext.Consumer>{(auth) => <Button onClick={() => void auth?.login('/customer', 'person@example.com', 'customer')}>Continue</Button>}</AuthContext.Consumer></AuthProvider></QueryClientProvider>);
    fireEvent.click(await screen.findByRole('button', { name: 'Continue' }));
    await waitFor(() => expect(signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/customer', entryPoint: 'customer' }, extraQueryParams: { prompt: 'login', login_hint: 'person@example.com' } }));
  });

  it('preserves merchant login intent in the same tab for the callback fallback', async () => {
    window.history.replaceState({}, '', '/');
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><AuthProvider><AuthContext.Consumer>{(auth) => <Button onClick={() => void auth?.login('/merchant/dashboard', 'business@example.com', 'merchant')}>Merchant sign in</Button>}</AuthContext.Consumer></AuthProvider></QueryClientProvider>);
    fireEvent.click(await screen.findByRole('button', { name: 'Merchant sign in' }));
    await waitFor(() => expect(signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/merchant/dashboard', entryPoint: 'merchant' }, extraQueryParams: { prompt: 'login', login_hint: 'business@example.com' } }));
    expect(JSON.parse(window.sessionStorage.getItem('zentro.auth-flow') ?? '{}')).toEqual({ returnTo: '/merchant/dashboard', entryPoint: 'merchant' });
  });

  it('restores the merchant intent when Keycloak callback state is missing', async () => {
    window.history.replaceState({}, '', '/callback?code=once&state=state');
    window.sessionStorage.setItem('zentro.auth-flow', JSON.stringify({ returnTo: '/merchant/dashboard', entryPoint: 'merchant' }));
    signinCallback.mockResolvedValueOnce({ expired: false, access_token: 'test-token', profile: { sub: 'customer-id', realm_access: { roles: ['CUSTOMER'] } }, state: undefined });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><AuthProvider><AuthContext.Consumer>{(auth) => <p>{auth?.user ? `${auth.user.entryPoint}:${auth.user.returnTo}` : 'No user'}</p>}</AuthContext.Consumer></AuthProvider></QueryClientProvider>);
    expect(await screen.findByText('merchant:/merchant/dashboard')).toBeInTheDocument();
    await waitFor(() => expect(window.sessionStorage.getItem('zentro.auth-flow')).toBeNull());
  });

  it('prefers the current merchant sign-in over stale customer callback state', async () => {
    window.history.replaceState({}, '', '/callback?code=once&state=state');
    window.sessionStorage.setItem('zentro.auth-flow', JSON.stringify({ returnTo: '/merchant/dashboard', entryPoint: 'merchant' }));
    signinCallback.mockResolvedValueOnce({ expired: false, access_token: 'test-token', profile: { sub: 'customer-id', realm_access: { roles: ['CUSTOMER'] } }, state: { returnTo: '/customer', entryPoint: 'customer' } });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><AuthProvider><AuthContext.Consumer>{(auth) => <p>{auth?.user ? `${auth.user.entryPoint}:${auth.user.returnTo}` : 'No user'}</p>}</AuthContext.Consumer></AuthProvider></QueryClientProvider>);
    expect(await screen.findByText('merchant:/merchant/dashboard')).toBeInTheDocument();
  });

  it('starts Google OIDC through Keycloak identity brokering', async () => {
    window.history.replaceState({}, '', '/');
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    render(<QueryClientProvider client={client}><AuthProvider><AuthContext.Consumer>{(auth) => <Button onClick={() => void auth?.login('/customer', undefined, 'customer', 'google')}>Google</Button>}</AuthContext.Consumer></AuthProvider></QueryClientProvider>);
    fireEvent.click(await screen.findByRole('button', { name: 'Google' }));
    await waitFor(() => expect(signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/customer', entryPoint: 'customer' }, extraQueryParams: { prompt: 'login', kc_idp_hint: 'google' } }));
  });
});
