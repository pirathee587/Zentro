import { describe, expect, it } from 'vitest';
import { clearLegacyStoredTokens, oidcConfig } from './oidcConfig';

describe('OIDC storage', () => {
  it('keeps access tokens in memory and stores only the redirect state in session storage', async () => {
    localStorage.clear();
    sessionStorage.clear();
    const userKey = 'oidc.user:zentro-web';
    const stateKey = 'oidc.state:zentro-web';
    await oidcConfig.userStore.set(userKey, JSON.stringify({ access_token: 'mock-access-token' }));
    expect(await oidcConfig.userStore.get(userKey)).toContain('mock-access-token');
    expect(localStorage.getItem(userKey)).toBeNull();
    expect(sessionStorage.getItem(userKey)).toBeNull();
    await oidcConfig.stateStore.set(stateKey, JSON.stringify({ state: 'redirect-state' }));
    expect(sessionStorage.getItem(`oidc.${stateKey}`)).toContain('redirect-state');
    await oidcConfig.userStore.remove(userKey);
    await oidcConfig.stateStore.remove(stateKey);
  });

  it('removes access tokens left by the previous persistent-storage setup', () => {
    localStorage.setItem('oidc.user:old-zentro-session', 'legacy-token');
    sessionStorage.setItem('oidc.user:old-zentro-session', 'legacy-token');
    sessionStorage.setItem('oidc.state:pending-login', 'non-secret-state');
    clearLegacyStoredTokens();
    expect(localStorage.getItem('oidc.user:old-zentro-session')).toBeNull();
    expect(sessionStorage.getItem('oidc.user:old-zentro-session')).toBeNull();
    expect(sessionStorage.getItem('oidc.state:pending-login')).toBe('non-secret-state');
  });
});
