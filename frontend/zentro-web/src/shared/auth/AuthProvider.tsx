import { useCallback, useEffect, useRef, useState, type PropsWithChildren } from 'react';
import { useQuery } from '@tanstack/react-query';
import type { User } from 'oidc-client-ts';
import { setAccessToken, apiClient } from '@/shared/api/apiClient';
import { AuthContext, type Auth, type EntryPoint } from './authContext';
import { clearLegacyStoredTokens, oidcManager } from './oidcConfig';
import { mapSupportedRoles } from '@/shared/config/roles';
import { queryClient } from '@/shared/api/queryClient';
import { ErrorState, Spinner } from '@/shared/ui';

let callbackPromise: Promise<User | undefined> | null = null;
function consumeSignInCallback(): Promise<User | undefined> {
  if (!callbackPromise) {
    const pending = oidcManager.signinCallback().then((user) => {
      window.history.replaceState({}, '', '/callback');
      return user;
    }).catch((error: unknown) => {
      callbackPromise = null;
      throw error;
    });
    callbackPromise = pending;
    return pending;
  }
  return callbackPromise;
}

export function AuthProvider({ children }: PropsWithChildren) {
  const [oidcUser, setOidcUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  const [authError, setAuthError] = useState('');
  const patchedProfiles = useRef(new Set<string>());
  useEffect(() => {
    let active = true;
    clearLegacyStoredTokens();
    const path = window.location.pathname;
    const load = path === '/silent-callback'
      ? oidcManager.signinSilentCallback().then(() => undefined)
      : path === '/callback'
        ? consumeSignInCallback()
        : oidcManager.getUser().then(async (storedUser) => {
          if (storedUser && !storedUser.expired) return storedUser;
          try {
            return await oidcManager.signinSilent({ extraQueryParams: { prompt: 'none' } });
          } catch {
            return undefined;
          }
        });
    load.then((u) => { if (active) { const current = u && !u.expired ? u : null; setAccessToken(current?.access_token); setOidcUser(current); setLoading(false); } }).catch(() => { if (active) setLoading(false); });
    const changed = (u: User) => { setAccessToken(u.access_token); setOidcUser(u); };
    const unloaded = () => { setAccessToken(undefined); setOidcUser(null); };
    oidcManager.events.addUserLoaded(changed); oidcManager.events.addUserUnloaded(unloaded);
    return () => { active = false; oidcManager.events.removeUserLoaded(changed); oidcManager.events.removeUserUnloaded(unloaded); };
  }, []);
  useEffect(() => { setAccessToken(oidcUser?.access_token); }, [oidcUser?.access_token]);
  const me = useQuery({ queryKey: ['current-user', oidcUser?.access_token], enabled: Boolean(oidcUser?.access_token), queryFn: async () => { setAccessToken(oidcUser?.access_token); return (await apiClient.get('/api/v1/users/me')).data; }, retry: false });
  const profile = (me.data ?? {}) as { id?: string | number; name?: string; phone?: string; roles?: unknown; realmRoles?: unknown };
  const claims = (oidcUser?.profile ?? {}) as Record<string, unknown>;
  const claimPhone = typeof claims.phone_number === 'string' ? claims.phone_number : typeof claims.phone === 'string' ? claims.phone : '';
  const claimName = typeof claims.name === 'string' ? claims.name : [claims.given_name, claims.family_name].filter((part): part is string => typeof part === 'string').join(' ');
  useEffect(() => {
    const profileId = String(profile.id ?? oidcUser?.profile.sub ?? '');
    if (!oidcUser || !me.data || me.isPending || me.isError || profile.phone || !profileId || patchedProfiles.current.has(profileId)) return;
    patchedProfiles.current.add(profileId);
    void apiClient.patch('/api/v1/users/me', { name: claimName, phone: claimPhone }).then(() => queryClient.invalidateQueries({ queryKey: ['current-user'] })).catch(() => undefined);
  }, [claimName, claimPhone, me.data, me.isError, me.isPending, oidcUser, profile.id, profile.phone]);
  const realmAccess = oidcUser?.profile.realm_access as { roles?: string[] } | undefined;
  const realmRoles = realmAccess?.roles ?? [];
  const apiRoleValue = profile.roles ?? profile.realmRoles;
  const apiRoles = Array.isArray(apiRoleValue) ? apiRoleValue.filter((role): role is string => typeof role === 'string') : [];
  const state = oidcUser?.state as { returnTo?: string; entryPoint?: EntryPoint } | undefined;
  const mappedRoles = mapSupportedRoles(realmRoles, apiRoles);
  const user: Auth['user'] = oidcUser ? { id: String(profile.id ?? oidcUser.profile.sub), name: profile.name ?? claimName, accessToken: oidcUser.access_token ?? '', returnTo: state?.returnTo, entryPoint: state?.entryPoint, roles: mappedRoles } : null;
  const login = useCallback(async (returnTo = '/', hint?: string, entryPoint: EntryPoint = 'customer') => {
    setAuthError('');
    try {
      await oidcManager.signinRedirect({ state: { returnTo, entryPoint }, extraQueryParams: { prompt: 'login', ...(hint ? { login_hint: hint } : {}) } });
    } catch {
      setAuthError('Cannot reach the sign-in service. Start Keycloak and try again.');
    }
  }, []);
  const register = useCallback(async (returnTo = '/customer', entryPoint: EntryPoint = 'customer') => {
    setAuthError('');
    try {
      await oidcManager.signinRedirect({ state: { returnTo, entryPoint }, extraQueryParams: { prompt: 'create', kc_action: 'register' } });
    } catch {
      setAuthError('Cannot reach the sign-in service. Start Keycloak and try again.');
    }
  }, []);
  const logout = useCallback(async (returnTo = '/') => { setAccessToken(undefined); await oidcManager.signoutRedirect({ post_logout_redirect_uri: `${window.location.origin}${returnTo}` }); }, []);
  if (loading) return <main className="screen-loading"><Spinner label="Loading your account" /></main>;
  if (window.location.pathname === '/silent-callback') return <main className="screen-loading" aria-label="Completing session check" />;
  return <AuthContext.Provider value={{ user, setUser: () => undefined, login, register, logout, loading, authError }}>{authError && <div className="auth-api-notice"><ErrorState message={authError} /></div>}{children}</AuthContext.Provider>;
}
