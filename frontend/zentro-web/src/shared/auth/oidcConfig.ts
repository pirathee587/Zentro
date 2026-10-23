import { InMemoryWebStorage, UserManager, WebStorageStateStore } from 'oidc-client-ts';
import { env } from '@/shared/config/env';
const memoryStore = new InMemoryWebStorage();
export const oidcConfig = { authority: `${env.VITE_KEYCLOAK_URL}/realms/${env.VITE_KEYCLOAK_REALM}`, client_id: env.VITE_KEYCLOAK_CLIENT_ID, redirect_uri: `${window.location.origin}/callback`, silent_redirect_uri: `${window.location.origin}/silent-callback`, post_logout_redirect_uri: `${window.location.origin}/`, response_type: 'code', scope: 'openid profile email roles', automaticSilentRenew: true, silentRequestTimeoutInSeconds: 15, userStore: new WebStorageStateStore({ store: memoryStore }), stateStore: new WebStorageStateStore({ store: window.sessionStorage }) };
export const oidcManager = new UserManager(oidcConfig);

export function clearLegacyStoredTokens() {
  for (const store of [window.localStorage, window.sessionStorage]) {
    for (let index = store.length - 1; index >= 0; index -= 1) {
      const key = store.key(index);
      if (key?.startsWith('oidc.user:')) store.removeItem(key);
    }
  }
}
