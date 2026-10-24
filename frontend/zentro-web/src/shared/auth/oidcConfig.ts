import { InMemoryWebStorage, UserManager, WebStorageStateStore } from 'oidc-client-ts';
import { env } from '@/shared/config/env';

const authority = `${env.VITE_KEYCLOAK_URL}/realms/${env.VITE_KEYCLOAK_REALM}`;

/** OIDC user/token data stays in memory. Only short-lived authorization state uses sessionStorage. */
export const oidcConfig = {
  authority,
  client_id: env.VITE_KEYCLOAK_CLIENT_ID,
  redirect_uri: `${window.location.origin}/callback`,
  silent_redirect_uri: `${window.location.origin}/silent-callback`,
  post_logout_redirect_uri: `${window.location.origin}/`,
  response_type: 'code',
  scope: 'openid profile email roles',
  automaticSilentRenew: true,
  silentRequestTimeoutInSeconds: 15,
  userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
  stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
};

export const oidcManager = new UserManager(oidcConfig);

/** Remove tokens left by older builds that persisted OIDC users in browser storage. */
export function clearLegacyStoredTokens(): void {
  for (const storage of [window.localStorage, window.sessionStorage]) {
    const legacyKeys = Array.from({ length: storage.length }, (_, index) => storage.key(index))
      .filter((key): key is string => key?.startsWith('oidc.user:') === true);
    legacyKeys.forEach((key) => storage.removeItem(key));
  }
}
