import { useCallback, useEffect, useState, type PropsWithChildren } from 'react';
import { useQuery } from '@tanstack/react-query';
import type { User } from 'oidc-client-ts';
import { setAccessToken, apiClient } from '@/shared/api/apiClient';
import { AuthContext, type Auth } from './authContext';
import { oidcManager } from './oidcConfig';
import { mapSupportedRoles } from '@/shared/config/roles';
import { Spinner } from '@/shared/ui';

let callbackPromise: Promise<User> | null = null;
function consumeSignInCallback() {
  if (!callbackPromise) callbackPromise = oidcManager.signinCallback().then((user) => { window.history.replaceState({}, '', '/callback'); return user; }).catch((error: unknown) => { callbackPromise = null; throw error; });
  return callbackPromise;
}

export function AuthProvider({ children }: PropsWithChildren) {
  const [oidcUser, setOidcUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);
  useEffect(() => {
    let active = true;
    const path = window.location.pathname;
    const load = path === '/callback' ? consumeSignInCallback() : oidcManager.getUser();
    load.then((u) => { if (active) { setOidcUser(u && !u.expired ? u : null); setLoading(false); } }).catch(() => { if (active) setLoading(false); });
    const changed = (u: User) => { setOidcUser(u); };
    const unloaded = () => setOidcUser(null);
    oidcManager.events.addUserLoaded(changed); oidcManager.events.addUserUnloaded(unloaded);
    return () => { active = false; oidcManager.events.removeUserLoaded(changed); oidcManager.events.removeUserUnloaded(unloaded); };
  }, []);
  useEffect(() => { setAccessToken(oidcUser?.access_token); }, [oidcUser?.access_token]);
  const me = useQuery({ queryKey: ['current-user', oidcUser?.access_token], enabled: Boolean(oidcUser?.access_token), queryFn: async () => { setAccessToken(oidcUser?.access_token); return (await apiClient.get('/api/v1/users/me')).data; }, retry: false });
  const profile = (me.data ?? {}) as { id?: string | number; roles?: unknown; realmRoles?: unknown };
  const realmAccess = oidcUser?.profile.realm_access as { roles?: string[] } | undefined;
  const realmRoles = realmAccess?.roles ?? [];
  const apiRoleValue = profile.roles ?? profile.realmRoles;
  const apiRoles = Array.isArray(apiRoleValue) ? apiRoleValue.filter((role): role is string => typeof role === 'string') : [];
  const state = oidcUser?.state as { returnTo?: string } | undefined;
  const user: Auth['user'] = oidcUser ? { id: String(profile.id ?? oidcUser.profile.sub), accessToken: oidcUser.access_token ?? '', returnTo: state?.returnTo, roles: mapSupportedRoles(realmRoles, apiRoles) } : null;
  const login = useCallback((returnTo = '/', hint?: string) => oidcManager.signinRedirect({ state: { returnTo }, ...(hint ? { extraQueryParams: { login_hint: hint } } : {}) }), []);
  const register = useCallback((returnTo = '/customer') => oidcManager.signinRedirect({ state: { returnTo }, extraQueryParams: { prompt: 'create', kc_action: 'register' } }), []);
  const logout = useCallback(async (returnTo = '/') => { setAccessToken(undefined); await oidcManager.signoutRedirect({ post_logout_redirect_uri: `${window.location.origin}${returnTo}` }); }, []);
  if (loading || (oidcUser && me.isPending)) return <main className="screen-loading"><Spinner label="Loading your account" /></main>;
  return <AuthContext.Provider value={{ user, setUser: () => undefined, login, register, logout, loading }}>{children}</AuthContext.Provider>;
}
