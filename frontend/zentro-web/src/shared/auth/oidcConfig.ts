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

const originalGetUser = oidcManager.getUser.bind(oidcManager);
oidcManager.getUser = async function () {
  if (typeof window !== 'undefined') {
    const e2eUser = window.sessionStorage.getItem('zentro.e2e-user');
    if (e2eUser) {
      try {
        return JSON.parse(e2eUser);
      } catch {
        // Fall back to normal user store
      }
    }
  }
  return originalGetUser();
};

const originalRemoveUser = oidcManager.removeUser.bind(oidcManager);
oidcManager.removeUser = async function () {
  if (typeof window !== 'undefined') {
    window.sessionStorage.removeItem('zentro.e2e-user');
  }
  return originalRemoveUser();
};

const originalSigninSilent = oidcManager.signinSilent.bind(oidcManager);
oidcManager.signinSilent = async function (args) {
  if (typeof window !== 'undefined' && (window as unknown as { __E2E__?: boolean }).__E2E__) {
    return null;
  }
  return originalSigninSilent(args);
};

/** Remove tokens left by older builds that persisted OIDC users in browser storage. */
export function clearLegacyStoredTokens(): void {
  for (const storage of [window.localStorage, window.sessionStorage]) {
    const legacyKeys = Array.from({ length: storage.length }, (_, index) => storage.key(index))
      .filter((key): key is string => key?.startsWith('oidc.user:') === true);
    legacyKeys.forEach((key) => storage.removeItem(key));
  }
}
