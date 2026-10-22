import { describe, expect, it } from 'vitest';
import { InMemoryWebStorage } from 'oidc-client-ts';
import { oidcConfig } from './oidcConfig';

describe('OIDC storage', () => {
  it('keeps user tokens in memory and stores only redirect state in session storage', () => {
    expect(oidcConfig.userStore.store).toBeInstanceOf(InMemoryWebStorage);
    expect(oidcConfig.stateStore.store).toBe(window.sessionStorage);
  });
});
