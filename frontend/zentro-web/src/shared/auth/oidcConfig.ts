import { UserManager, WebStorageStateStore, InMemoryWebStorage } from 'oidc-client-ts';
import { env } from '@/shared/config/env';
export const oidcConfig = { authority: `${env.VITE_KEYCLOAK_URL}/realms/${env.VITE_KEYCLOAK_REALM}`, client_id: env.VITE_KEYCLOAK_CLIENT_ID, redirect_uri: `${window.location.origin}/callback`, post_logout_redirect_uri: `${window.location.origin}/`, response_type: 'code', scope: 'openid profile email', userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }), stateStore: new WebStorageStateStore({ store: window.sessionStorage }) };
export const oidcManager = new UserManager(oidcConfig);
