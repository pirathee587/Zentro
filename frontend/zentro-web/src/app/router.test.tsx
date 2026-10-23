import { render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';

const { getUser, signinSilent } = vi.hoisted(() => ({
  getUser: vi.fn().mockResolvedValue(null),
  signinSilent: vi.fn().mockRejectedValue(new Error('login_required')),
}));
vi.mock('@/shared/auth/oidcConfig', () => ({
  clearLegacyStoredTokens: vi.fn(),
  oidcManager: { getUser, signinSilent, events: { addUserLoaded: vi.fn(), removeUserLoaded: vi.fn(), addUserUnloaded: vi.fn(), removeUserUnloaded: vi.fn() } },
}));

import { Providers } from './providers';
import { AppRouter } from './router';

describe('portal routing', () => {
  it('renders a lazy-loaded public tracking route', async () => {
    render(<Providers><MemoryRouter initialEntries={['/track/ZTR-ABCDEFG234']}><AppRouter /></MemoryRouter></Providers>);
    expect(await screen.findByRole('heading', { name: /Your parcel.s journey/ }, { timeout: 5000 })).toBeInTheDocument();
  });
});
