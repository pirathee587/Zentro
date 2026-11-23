import { useCallback, useEffect, useMemo, useRef, useState, type PropsWithChildren } from 'react';
import { useQuery } from '@tanstack/react-query';
import axios from 'axios';
import type { User } from 'oidc-client-ts';
import { apiClient, setAccessToken, withBearerToken } from '@/shared/api/apiClient';
import { queryClient } from '@/shared/api/queryClient';
import { ErrorState, Spinner } from '@/shared/ui';
import { mapSupportedRoles } from '@/shared/config/roles';
import { AuthContext, type AccountStatus, type Auth, type EntryPoint, type IdentityProvider, type LoginOptions, type MerchantStatus } from './authContext';
import { clearLegacyStoredTokens, oidcManager } from './oidcConfig';

type OidcProfile = User['profile'] & {
  realm_access?: { roles?: unknown };
  phone_number?: string;
  phone?: string;
};

type AppProfile = {
  id?: string | number;
  email?: string;
  name?: string;
  phone?: string;
  roles?: unknown;
  accountStatus?: unknown;
  enabled?: unknown;
  merchantStatus?: unknown;
};

let callbackInFlight: Promise<User | null> | null = null;
/** One authorization redirect per page load: repeated guard renders must not start competing flows. */
let signinRedirectInFlight = false;
if (typeof window !== 'undefined') {
  // Returning with the browser Back button restores this page from bfcache; allow a new sign-in.
  window.addEventListener('pageshow', (event) => {
    if (event.persisted) signinRedirectInFlight = false;
  });
}

/** Test hook: each test starts without an in-flight authorization redirect. */
export function resetSigninRedirectGuardForTests(): void {
  signinRedirectInFlight = false;
}
const AUTH_FLOW_STORAGE_KEY = 'zentro.auth-flow';
const SSO_RESTORE_SUPPRESSED_KEY = 'zentro.sso-restore-suppressed';
const SWITCH_ACCOUNT_QUERY_KEY = 'switch_account';
const SIGNED_OUT_QUERY_KEY = 'zentro_signed_out';
const IDENTITY_CONFLICT_QUERY_KEY = 'identity_conflict';
export const IDENTITY_CONFLICT_MESSAGE = 'This email address is already registered with a different Zentro sign-in. '
  + 'Sign in with the method you originally used (for example your email and password), or contact Zentro support to link your sign-in methods.';

/** S1 refused to create a second business profile for an email owned by another identity. */
function isIdentityConflict(error: unknown): boolean {
  return axios.isAxiosError(error) && error.response?.status === 409
    && (error.response.data as { code?: unknown } | undefined)?.code === 'ACCOUNT_IDENTITY_CONFLICT';
}

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

function isExplicitSignOut(): boolean {
  const query = new URLSearchParams(window.location.search);
  return window.sessionStorage.getItem(SSO_RESTORE_SUPPRESSED_KEY) === '1'
    || query.get(SWITCH_ACCOUNT_QUERY_KEY) === '1'
    || query.get(SIGNED_OUT_QUERY_KEY) === '1';
}

function getPostLogoutRedirectUri(returnTo: string): string {
  let target: URL;
  try {
    target = new URL(returnTo, window.location.origin);
  } catch {
    target = new URL('/', window.location.origin);
  }

  if (target.origin !== window.location.origin) target = new URL('/', window.location.origin);
  if (target.searchParams.get(SWITCH_ACCOUNT_QUERY_KEY) !== '1') {
    target.searchParams.set(SIGNED_OUT_QUERY_KEY, '1');
  }
  return target.toString();
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
  const merchantRoleRefreshAttempted = useRef(new Set<string>());

  useEffect(() => {
    let mounted = true;
    clearLegacyStoredTokens();
    if (new URLSearchParams(window.location.search).get(IDENTITY_CONFLICT_QUERY_KEY) === '1') {
      setAuthError(IDENTITY_CONFLICT_MESSAGE);
    }

    async function restoreSession(): Promise<User | null> {
      try {
        const path = window.location.pathname;
        if (path === '/silent-callback') {
          await oidcManager.signinSilentCallback();
          return null;
        }
        if (path === '/callback') return completeAuthorizationCallback();

        if (isExplicitSignOut()) {
          await oidcManager.removeUser();
          return null;
        }

        const existing = await oidcManager.getUser();
        if (existing && !existing.expired) return existing;

        try {
          return await oidcManager.signinSilent({ extraQueryParams: { prompt: 'none' } }) ?? null;
        } catch {
          return null;
        }
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
    queryFn: async ({ signal }) => {
      try {
        return (await apiClient.get<AppProfile>('/api/v1/users/me', {
          ...withBearerToken(accessToken),
          signal,
        })).data;
      } catch (error) {
        if (axios.isAxiosError(error) && error.response?.status === 404) {
          // The user was deleted by admin or does not exist in DB: evict dead session immediately.
          try {
            await oidcManager.removeUser();
          } catch {
            // ignore
          }
          setAccessToken(undefined);
          setOidcUser(null);
          throw error;
        }
        // Access tokens can expire while the Keycloak session is still active.
        // Renew once and retry the profile request before treating the user as signed out.
        if (!axios.isAxiosError(error) || error.response?.status !== 401) throw error;
        const renewedUser = await oidcManager.signinSilent({ extraQueryParams: { prompt: 'none' } });
        if (!renewedUser || renewedUser.expired) throw error;
        setAccessToken(renewedUser.access_token);
        setOidcUser(renewedUser);
        return (await apiClient.get<AppProfile>('/api/v1/users/me', {
          ...withBearerToken(renewedUser.access_token),
          signal,
        })).data;
      }
    },
  });

  const tokenProfile = oidcUser?.profile as OidcProfile | undefined;
  const appProfile = profileQuery.data ?? {};
  const idTokenRoles = getRealmRoles(tokenProfile);
  const tokenHasMerchantRole = idTokenRoles.includes('MERCHANT');
  const roles = mapSupportedRoles(idTokenRoles);
  const oidcState = oidcUser?.state as { returnTo?: string; entryPoint?: EntryPoint } | undefined;
  const pendingAuthFlow = window.location.pathname === '/callback' ? readPendingAuthFlow() : undefined;
  const pendingMerchantFlow = pendingAuthFlow?.entryPoint === 'merchant' ? pendingAuthFlow : undefined;
  const enabledStatus = appProfile.enabled === false ? 'DISABLED' : appProfile.enabled === true ? 'ACTIVE' : undefined;
  const accountStatus = readStatus<AccountStatus>(appProfile.accountStatus, ['ACTIVE', 'DISABLED', 'SUSPENDED']) ?? enabledStatus;
  const merchantStatus = readStatus<MerchantStatus>(appProfile.merchantStatus, ['PENDING', 'APPROVED', 'REJECTED']);

  const user: Auth['user'] = oidcUser ? {
    id: String(tokenProfile?.sub ?? ''),
    userId: appProfile.id ? String(appProfile.id) : undefined,
    email: (appProfile.email ?? tokenProfile?.email) ? String(appProfile.email ?? tokenProfile?.email) : undefined,
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
    const subject = tokenProfile?.sub;
    if (!subject
      || !accessToken
      || !profileQuery.data
      || profileQuery.isError
      || appProfile.merchantStatus !== 'APPROVED'
      || tokenHasMerchantRole
      || merchantRoleRefreshAttempted.current.has(subject)) return;

    // Approval can grant MERCHANT after the current ID token was issued. Refresh once,
    // then rely on the new Keycloak role claim; profile approval alone never grants access.
    merchantRoleRefreshAttempted.current.add(subject);
    let mounted = true;
    void oidcManager.signinSilent({ extraQueryParams: { prompt: 'none' } })
      .then((renewedUser) => {
        if (!mounted || !renewedUser || renewedUser.expired) return;
        const renewedRoles = getRealmRoles(renewedUser.profile as OidcProfile);
        if (!renewedRoles.includes('MERCHANT')) return;
        setAccessToken(renewedUser.access_token);
        setOidcUser(renewedUser);
      })
      .catch(() => undefined);

    return () => { mounted = false; };
  }, [accessToken, appProfile.merchantStatus, profileQuery.data, profileQuery.isError, tokenHasMerchantRole, tokenProfile?.sub]);

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
      ...withBearerToken(accessToken),
    })
      .then(() => queryClient.invalidateQueries({ queryKey: ['current-user', tokenProfile?.sub] }))
      .catch(() => profilePatchInFlight.current.delete(profileId));
  }, [accessToken, appProfile.id, appProfile.phone, profileQuery.data, profileQuery.isError, tokenProfile]);

  const login = useCallback(async (
    returnTo = '/',
    emailHint?: string,
    entryPoint: EntryPoint = 'customer',
    identityProvider?: IdentityProvider,
    options?: LoginOptions,
  ) => {
    if (signinRedirectInFlight) return;
    signinRedirectInFlight = true;
    setAuthError('');
    const suppressRestoreOnFailure = window.sessionStorage.getItem(SSO_RESTORE_SUPPRESSED_KEY) === '1';
    window.sessionStorage.removeItem(SSO_RESTORE_SUPPRESSED_KEY);
    // After an explicit sign-out the previous account must never be reused silently.
    const forceCredentials = options?.reauthenticate !== false || suppressRestoreOnFailure;
    try {
      document.cookie = `zentro_app_origin=${encodeURIComponent(window.location.origin)}; Max-Age=900; Path=/; SameSite=Lax`;
      savePendingAuthFlow({ returnTo, entryPoint });
      await oidcManager.signinRedirect({
        state: { returnTo, entryPoint },
        extraQueryParams: {
          ...(forceCredentials ? { prompt: 'login' } : {}),
          ...(emailHint ? { login_hint: emailHint.trim() } : {}),
          ...(identityProvider ? { kc_idp_hint: identityProvider } : {}),
        },
      });
    } catch {
      signinRedirectInFlight = false;
      if (suppressRestoreOnFailure) window.sessionStorage.setItem(SSO_RESTORE_SUPPRESSED_KEY, '1');
      window.sessionStorage.removeItem(AUTH_FLOW_STORAGE_KEY);
      setAuthError('Sign-in is temporarily unavailable. Please refresh the page and try again.');
    }
  }, []);

  const register = useCallback(async (returnTo = '/customer', entryPoint: EntryPoint = 'customer') => {
    setAuthError('');
    const suppressRestoreOnFailure = window.sessionStorage.getItem(SSO_RESTORE_SUPPRESSED_KEY) === '1';
    window.sessionStorage.removeItem(SSO_RESTORE_SUPPRESSED_KEY);
    try {
      // Keycloak is on another localhost port, but localhost cookies are shared
      // across ports. The login theme uses this short-lived hint for its return link.
      document.cookie = `zentro_app_origin=${encodeURIComponent(window.location.origin)}; Max-Age=900; Path=/; SameSite=Lax`;
      document.cookie = `zentro_entry_point=${encodeURIComponent(entryPoint)}; Max-Age=900; Path=/; SameSite=Lax`;
      savePendingAuthFlow({ returnTo, entryPoint });
      await oidcManager.signinRedirect({
        state: { returnTo, entryPoint },
        extraQueryParams: { prompt: 'create', kc_action: 'register' },
      });
    } catch {
      if (suppressRestoreOnFailure) window.sessionStorage.setItem(SSO_RESTORE_SUPPRESSED_KEY, '1');
      window.sessionStorage.removeItem(AUTH_FLOW_STORAGE_KEY);
      setAuthError('Registration is temporarily unavailable. Please refresh the page and try again.');
    }
  }, []);

  const logout = useCallback(async (returnTo = '/') => {
    window.sessionStorage.setItem(SSO_RESTORE_SUPPRESSED_KEY, '1');
    setAccessToken(undefined);
    setOidcUser(null);
    setAuthError(returnTo.includes(`${IDENTITY_CONFLICT_QUERY_KEY}=1`) ? IDENTITY_CONFLICT_MESSAGE : '');
    window.sessionStorage.removeItem(AUTH_FLOW_STORAGE_KEY);
    await queryClient.cancelQueries();
    queryClient.clear();
    try {
      await oidcManager.signoutRedirect({
        post_logout_redirect_uri: getPostLogoutRedirectUri(returnTo),
      });
    } catch {
      try {
        await oidcManager.removeUser();
      } catch {
        // Keep the local application signed out even when Keycloak is unavailable.
      }
      setAuthError('We could not reach the sign-out service. Please try again.');
    }
  }, []);

  // Never keep a session whose identity S1 refused: end the Keycloak session too (no silent re-entry loop)
  // and explain the situation on the sign-in page.
  const identityConflict = isIdentityConflict(profileQuery.error);
  useEffect(() => {
    if (identityConflict) void logout(`/login?${IDENTITY_CONFLICT_QUERY_KEY}=1`);
  }, [identityConflict, logout]);

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
