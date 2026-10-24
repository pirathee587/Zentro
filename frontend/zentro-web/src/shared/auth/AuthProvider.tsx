import { useCallback, useEffect, useMemo, useRef, useState, type PropsWithChildren } from 'react';
import { useQuery } from '@tanstack/react-query';
import axios from 'axios';
import type { User } from 'oidc-client-ts';
import { apiClient, setAccessToken } from '@/shared/api/apiClient';
import { queryClient } from '@/shared/api/queryClient';
import { ErrorState, Spinner } from '@/shared/ui';
import { mapSupportedRoles } from '@/shared/config/roles';
import { AuthContext, type AccountStatus, type Auth, type EntryPoint, type IdentityProvider, type MerchantStatus } from './authContext';
import { clearLegacyStoredTokens, oidcManager } from './oidcConfig';

type OidcProfile = User['profile'] & {
  realm_access?: { roles?: unknown };
  phone_number?: string;
  phone?: string;
};

type AppProfile = {
  id?: string | number;
  name?: string;
  phone?: string;
  roles?: unknown;
  accountStatus?: unknown;
  enabled?: unknown;
  merchantStatus?: unknown;
};

let callbackInFlight: Promise<User | null> | null = null;
const AUTH_FLOW_STORAGE_KEY = 'zentro.auth-flow';

type AuthFlow = { returnTo: string; entryPoint: EntryPoint };

function readPendingAuthFlow(): AuthFlow | undefined {
  try {
    const value = window.sessionStorage.getItem(AUTH_FLOW_STORAGE_KEY);
    if (!value) return undefined;
    const flow = JSON.parse(value) as Partial<AuthFlow>;
    if (typeof flow.returnTo !== 'string' || !['customer', 'merchant', 'staff'].includes(flow.entryPoint ?? '')) return undefined;
    return { returnTo: flow.returnTo, entryPoint: flow.entryPoint as EntryPoint };
  } catch {
    return undefined;
  }
}

function savePendingAuthFlow(flow: AuthFlow): void {
  window.sessionStorage.setItem(AUTH_FLOW_STORAGE_KEY, JSON.stringify(flow));
}

function completeAuthorizationCallback(): Promise<User | null> {
  if (callbackInFlight) return callbackInFlight;

  callbackInFlight = oidcManager.signinCallback()
    .then((user) => user ?? null)
    .finally(() => {
      window.history.replaceState({}, '', '/callback');
      callbackInFlight = null;
    });

  return callbackInFlight;
}

function readStatus<T extends string>(value: unknown, valid: readonly T[]): T | undefined {
  return typeof value === 'string' && valid.includes(value as T) ? value as T : undefined;
}

function getRealmRoles(profile: OidcProfile | undefined): string[] {
  const roles = profile?.realm_access?.roles;
  return Array.isArray(roles) ? roles.filter((role): role is string => typeof role === 'string') : [];
}

export function AuthProvider({ children }: PropsWithChildren) {
  const [oidcUser, setOidcUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [authError, setAuthError] = useState('');
  const profilePatchInFlight = useRef(new Set<string>());

  useEffect(() => {
    let mounted = true;
    clearLegacyStoredTokens();

    async function restoreSession(): Promise<User | null> {
      const path = window.location.pathname;
      if (path === '/silent-callback') {
        await oidcManager.signinSilentCallback();
        return null;
      }
      if (path === '/callback') return completeAuthorizationCallback();

      const existing = await oidcManager.getUser();
      if (existing && !existing.expired) return existing;

      try {
        return await oidcManager.signinSilent({ extraQueryParams: { prompt: 'none' } }) ?? null;
      } catch {
        return null;
      }
    }

    restoreSession()
      .then((user) => {
        if (!mounted) return;
        const activeUser = user && !user.expired ? user : null;
        setAccessToken(activeUser?.access_token);
        setOidcUser(activeUser);
        setLoading(false);
      })
      .catch(() => {
        if (!mounted) return;
        setAccessToken(undefined);
        setOidcUser(null);
        setAuthError('We could not complete sign in. Please try again.');
        setLoading(false);
      });

    const onUserLoaded = (user: User) => {
      setAccessToken(user.access_token);
      setOidcUser(user);
      setAuthError('');
    };
    const onUserUnloaded = () => {
      setAccessToken(undefined);
      setOidcUser(null);
    };

    oidcManager.events.addUserLoaded(onUserLoaded);
    oidcManager.events.addUserUnloaded(onUserUnloaded);

    return () => {
      mounted = false;
      oidcManager.events.removeUserLoaded(onUserLoaded);
      oidcManager.events.removeUserUnloaded(onUserUnloaded);
    };
  }, []);

  const accessToken = oidcUser?.access_token;
  useEffect(() => setAccessToken(accessToken), [accessToken]);

  const profileQuery = useQuery({
    queryKey: ['current-user', oidcUser?.profile.sub],
    enabled: Boolean(accessToken),
    queryFn: async () => {
      try {
        return (await apiClient.get<AppProfile>('/api/v1/users/me', {
          headers: { Authorization: `Bearer ${accessToken}` },
        })).data;
      } catch (error) {
        // Access tokens can expire while the Keycloak session is still active.
        // Renew once and retry the profile request before treating the user as signed out.
        if (!axios.isAxiosError(error) || error.response?.status !== 401) throw error;
        const renewedUser = await oidcManager.signinSilent({ extraQueryParams: { prompt: 'none' } });
        if (!renewedUser || renewedUser.expired) throw error;
        setAccessToken(renewedUser.access_token);
        setOidcUser(renewedUser);
        return (await apiClient.get<AppProfile>('/api/v1/users/me', {
          headers: { Authorization: `Bearer ${renewedUser.access_token}` },
        })).data;
      }
    },
    retry: false,
  });

  const tokenProfile = oidcUser?.profile as OidcProfile | undefined;
  const appProfile = profileQuery.data ?? {};
  const idTokenRoles = getRealmRoles(tokenProfile);
  const roles = mapSupportedRoles(idTokenRoles);
  const oidcState = oidcUser?.state as { returnTo?: string; entryPoint?: EntryPoint } | undefined;
  const pendingAuthFlow = window.location.pathname === '/callback' ? readPendingAuthFlow() : undefined;
  const pendingMerchantFlow = pendingAuthFlow?.entryPoint === 'merchant' ? pendingAuthFlow : undefined;
  const enabledStatus = appProfile.enabled === false ? 'DISABLED' : appProfile.enabled === true ? 'ACTIVE' : undefined;
  const accountStatus = readStatus<AccountStatus>(appProfile.accountStatus, ['ACTIVE', 'DISABLED', 'SUSPENDED']) ?? enabledStatus;
  const merchantStatus = readStatus<MerchantStatus>(appProfile.merchantStatus, ['PENDING', 'APPROVED', 'REJECTED']);

  const user: Auth['user'] = oidcUser ? {
    id: String(tokenProfile?.sub ?? ''),
    name: appProfile.name ?? tokenProfile?.name ?? [tokenProfile?.given_name, tokenProfile?.family_name].filter(Boolean).join(' '),
    roles,
    accessToken: accessToken ?? '',
    returnTo: pendingMerchantFlow?.returnTo ?? oidcState?.returnTo ?? pendingAuthFlow?.returnTo,
    entryPoint: pendingMerchantFlow?.entryPoint ?? oidcState?.entryPoint ?? pendingAuthFlow?.entryPoint,
    accountStatus,
    merchantStatus,
    profileLoaded: profileQuery.isFetched,
  } : null;

  useEffect(() => {
    if (window.location.pathname === '/callback' && oidcUser) {
      window.sessionStorage.removeItem(AUTH_FLOW_STORAGE_KEY);
    }
  }, [oidcUser]);

  useEffect(() => {
    const phone = tokenProfile?.phone_number ?? tokenProfile?.phone;
    const name = tokenProfile?.name ?? [tokenProfile?.given_name, tokenProfile?.family_name].filter(Boolean).join(' ');
    const profileId = String(appProfile.id ?? tokenProfile?.sub ?? '');
    if (!accessToken || !profileQuery.data || profileQuery.isError || appProfile.phone || !phone || !profileId || profilePatchInFlight.current.has(profileId)) return;

    profilePatchInFlight.current.add(profileId);
    void apiClient.patch('/api/v1/users/me', { name, phone }, {
      headers: { Authorization: `Bearer ${accessToken}` },
    })
      .then(() => queryClient.invalidateQueries({ queryKey: ['current-user', tokenProfile?.sub] }))
      .catch(() => profilePatchInFlight.current.delete(profileId));
  }, [accessToken, appProfile.id, appProfile.phone, profileQuery.data, profileQuery.isError, tokenProfile]);

  const login = useCallback(async (
    returnTo = '/',
    emailHint?: string,
    entryPoint: EntryPoint = 'customer',
    identityProvider?: IdentityProvider,
  ) => {
    setAuthError('');
    try {
      savePendingAuthFlow({ returnTo, entryPoint });
      await oidcManager.signinRedirect({
        state: { returnTo, entryPoint },
        extraQueryParams: {
          prompt: 'login',
          ...(emailHint ? { login_hint: emailHint.trim() } : {}),
          ...(identityProvider ? { kc_idp_hint: identityProvider } : {}),
        },
      });
    } catch {
      window.sessionStorage.removeItem(AUTH_FLOW_STORAGE_KEY);
      setAuthError('Cannot reach the sign-in service. Check Keycloak and try again.');
    }
  }, []);

  const register = useCallback(async (returnTo = '/customer', entryPoint: EntryPoint = 'customer') => {
    setAuthError('');
    try {
      // Keycloak is on another localhost port, but localhost cookies are shared
      // across ports. The login theme uses this short-lived hint for its return link.
      document.cookie = `zentro_entry_point=${encodeURIComponent(entryPoint)}; Max-Age=900; Path=/; SameSite=Lax`;
      savePendingAuthFlow({ returnTo, entryPoint });
      await oidcManager.signinRedirect({
        state: { returnTo, entryPoint },
        extraQueryParams: { prompt: 'create', kc_action: 'register' },
      });
    } catch {
      window.sessionStorage.removeItem(AUTH_FLOW_STORAGE_KEY);
      setAuthError('Cannot reach the registration service. Check Keycloak and try again.');
    }
  }, []);

  const logout = useCallback(async (returnTo = '/') => {
    setAccessToken(undefined);
    setOidcUser(null);
    setAuthError('');
    window.sessionStorage.removeItem(AUTH_FLOW_STORAGE_KEY);
    try {
      await oidcManager.signoutRedirect({
        post_logout_redirect_uri: `${window.location.origin}${returnTo}`,
      });
    } catch {
      setAuthError('We could not reach the sign-out service. Please try again.');
    }
  }, []);

  const refreshAccessToken = useCallback(async (force = false) => {
    let current = await oidcManager.getUser();
    const expiresSoon = current?.expires_at !== undefined && current.expires_at - Date.now() / 1000 < 60;
    if (force || !current || current.expired || expiresSoon) {
      current = await oidcManager.signinSilent({ extraQueryParams: { prompt: 'none' } }) ?? null;
    }
    if (!current || current.expired) {
      setAccessToken(undefined);
      setOidcUser(null);
      return undefined;
    }
    setAccessToken(current.access_token);
    setOidcUser(current);
    return current.access_token;
  }, []);

  const contextValue = useMemo<Auth>(() => ({
    user,
    setUser: () => undefined,
    login,
    register,
    logout,
    refreshAccessToken,
    loading,
    authError,
  }), [user, login, register, logout, refreshAccessToken, loading, authError]);

  if (loading) return <main className="screen-loading"><Spinner label="Loading your account" /></main>;
  if (window.location.pathname === '/silent-callback') return <main className="screen-loading" aria-label="Completing session check" />;

  return (
    <AuthContext.Provider value={contextValue}>
      {authError && <div className="auth-api-notice"><ErrorState message={authError} /></div>}
      {children}
    </AuthContext.Provider>
  );
}
