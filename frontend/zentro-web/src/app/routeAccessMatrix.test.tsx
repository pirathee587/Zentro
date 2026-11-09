import { render, screen, waitFor } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { MemoryRouter, useLocation } from 'react-router-dom';
import { delay, http, HttpResponse } from 'msw';
import { server } from '@/shared/testing/mocks/server';
import { queryClient } from '@/shared/api/queryClient';

const { getUser, signinSilent, signinRedirect } = vi.hoisted(() => ({
  getUser: vi.fn(),
  signinSilent: vi.fn(),
  signinRedirect: vi.fn(),
}));
vi.mock('@/shared/auth/oidcConfig', () => ({
  clearLegacyStoredTokens: vi.fn(),
  oidcManager: {
    getUser, signinSilent, signinRedirect,
    events: { addUserLoaded: vi.fn(), removeUserLoaded: vi.fn(), addUserUnloaded: vi.fn(), removeUserUnloaded: vi.fn() },
  },
}));

import { Providers } from './providers';
import { AppRouter } from './router';

/**
 * Route access matrix (ZD-082): every protected route group, the open routes and an unknown path, for each role and
 * account state. Guards are UX only; the backend still authorizes every request.
 */
type Profile = { accountStatus?: string; merchantStatus?: string };
type Who = { label: string; roles?: string[]; profile?: Profile };

const visitor: Who = { label: 'visitor' };
const customer: Who = { label: 'CUSTOMER', roles: ['CUSTOMER'], profile: { accountStatus: 'ACTIVE' } };
const merchant: Who = { label: 'approved MERCHANT', roles: ['MERCHANT'], profile: { accountStatus: 'ACTIVE', merchantStatus: 'APPROVED' } };
const customerMerchant: Who = { label: 'CUSTOMER + approved MERCHANT', roles: ['CUSTOMER', 'MERCHANT'], profile: { accountStatus: 'ACTIVE', merchantStatus: 'APPROVED' } };
const courier: Who = { label: 'COURIER', roles: ['COURIER'], profile: { accountStatus: 'ACTIVE' } };
const dispatcher: Who = { label: 'DISPATCHER', roles: ['DISPATCHER'], profile: { accountStatus: 'ACTIVE' } };
const disabledCustomer: Who = { label: 'DISABLED CUSTOMER', roles: ['CUSTOMER'], profile: { accountStatus: 'DISABLED' } };
const suspendedDispatcher: Who = { label: 'SUSPENDED DISPATCHER', roles: ['DISPATCHER'], profile: { accountStatus: 'SUSPENDED' } };
const adminOnly: Who = { label: 'ADMIN-only token', roles: ['ADMIN'], profile: { accountStatus: 'ACTIVE' } };
const serviceOnly: Who = { label: 'SERVICE-only token', roles: ['SERVICE'], profile: { accountStatus: 'ACTIVE' } };

/** Expected result: open (renders at the same path), forbidden, sign-in, or a redirect to another path. */
type Outcome = 'open' | 'forbidden' | 'sign-in' | `redirect:${string}`;
const PROTECTED = ['/customer', '/merchant/register-business', '/merchant/dashboard', '/courier', '/dispatcher', '/admin/users'] as const;

const matrix: Array<[Who, Outcome[]]> = [
  //                  /customer    register-business  /merchant/dashboard  /courier     /dispatcher  /admin/users
  [visitor,          ['sign-in',   'sign-in',         'sign-in',           'sign-in',   'sign-in',   'sign-in']],
  [customer,         ['open',      'open',            'forbidden',         'forbidden', 'forbidden', 'forbidden']],
  [merchant,         ['forbidden', 'forbidden',       'open',              'forbidden', 'forbidden', 'forbidden']],
  [customerMerchant, ['open',      'open',            'open',              'forbidden', 'forbidden', 'forbidden']],
  [courier,          ['forbidden', 'forbidden',       'forbidden',         'open',      'forbidden', 'forbidden']],
  [dispatcher,       ['forbidden', 'forbidden',       'forbidden',         'forbidden', 'open',      'redirect:/dispatcher']],
  [disabledCustomer, ['forbidden', 'forbidden',       'forbidden',         'forbidden', 'forbidden', 'forbidden']],
  [suspendedDispatcher, ['forbidden', 'forbidden',    'forbidden',         'forbidden', 'forbidden', 'forbidden']],
  [adminOnly,        ['forbidden', 'forbidden',       'forbidden',         'forbidden', 'forbidden', 'forbidden']],
  [serviceOnly,      ['forbidden', 'forbidden',       'forbidden',         'forbidden', 'forbidden', 'forbidden']],
];

/** A landmark that proves the right portal rendered, not just that the guard let the path through. */
const PORTAL_LANDMARK: Record<string, () => HTMLElement> = {
  '/customer': () => screen.getByRole('navigation', { name: 'Customer Navigation' }),
  '/merchant/register-business': () => screen.getByRole('heading', { level: 1 }),
  '/merchant/dashboard': () => screen.getByRole('navigation', { name: 'Merchant navigation' }),
  '/courier': () => screen.getByRole('navigation', { name: 'Courier navigation' }),
  '/dispatcher': () => screen.getByRole('navigation', { name: 'Dispatcher navigation' }),
};

function Location() {
  return <output data-testid="location">{useLocation().pathname}</output>;
}

function signIn(who: Who) {
  if (!who.roles) {
    getUser.mockResolvedValue(null);
    return;
  }
  const sub = `${who.label.replace(/\W+/g, '-')}-${Math.random().toString(36).slice(2)}`;
  getUser.mockResolvedValue({ expired: false, access_token: `${sub}-token`, profile: { sub, realm_access: { roles: who.roles } } });
  server.use(http.get('*/api/v1/users/me', () => HttpResponse.json({ id: sub, name: who.label, roles: who.roles, ...who.profile })));
}

function renderApp(path: string) {
  return render(<Providers><MemoryRouter initialEntries={[path]}><AppRouter /><Location /></MemoryRouter></Providers>);
}

const location = () => screen.getByTestId('location').textContent;

async function expectOutcome(path: string, outcome: Outcome) {
  if (outcome === 'sign-in') {
    await waitFor(() => expect(signinRedirect).toHaveBeenCalled());
    expect(signinRedirect.mock.calls.at(-1)?.[0]?.state?.returnTo).toBe(path);
    expect(screen.getByText(/Redirecting to sign in/i)).toBeInTheDocument();
    return;
  }
  if (outcome === 'forbidden') {
    await waitFor(() => expect(location()).toBe('/forbidden'), { timeout: 10000 });
    expect(screen.getByRole('heading', { name: 'Forbidden' })).toBeInTheDocument();
    return;
  }
  if (outcome.startsWith('redirect:')) {
    const target = outcome.slice('redirect:'.length);
    await waitFor(() => expect(location()).toBe(target), { timeout: 10000 });
    expect(await screen.findByRole('navigation', { name: 'Dispatcher navigation' }, { timeout: 10000 })).toBeInTheDocument();
    return;
  }
  await waitFor(() => expect(PORTAL_LANDMARK[path]()).toBeInTheDocument(), { timeout: 15000 });
  expect(location()).toBe(path);
  expect(screen.queryByRole('heading', { name: 'Forbidden' })).not.toBeInTheDocument();
}

beforeEach(() => {
  signinSilent.mockRejectedValue(new Error('login_required'));
  signinRedirect.mockResolvedValue(undefined);
  // Pages load their own data; any API call other than the profile gets an empty answer.
  server.use(http.all('*/api/*', () => HttpResponse.json([])));
  // No merchant application yet, so the application page stays on /merchant/register-business.
  server.use(http.get('*/api/v1/merchants/me', () => HttpResponse.json({ title: 'Not Found' }, { status: 404 })));
});

afterEach(() => {
  queryClient.clear();
  window.sessionStorage.clear();
  vi.clearAllMocks();
});

describe('route access matrix', () => {
  for (const [who, outcomes] of matrix) {
    describe(who.label, () => {
      PROTECTED.forEach((path, column) => {
        it(`${path} -> ${outcomes[column]}`, async () => {
          signIn(who);
          renderApp(path);
          await expectOutcome(path, outcomes[column]);
        }, 30000);
      });

      it('/styleguide and /forbidden stay open', async () => {
        signIn(who);
        renderApp('/styleguide');
        expect(await screen.findByRole('heading', { name: 'Style guide' })).toBeInTheDocument();
      });

      it('an unknown path shows page not found', async () => {
        signIn(who);
        renderApp('/no-such-page');
        expect(await screen.findByRole('heading', { name: 'Page not found' }, { timeout: 10000 })).toBeInTheDocument();
        expect(location()).toBe('/no-such-page');
      }, 30000);
    });
  }

  it('/forbidden renders for a visitor without starting sign in', async () => {
    signIn(visitor);
    renderApp('/forbidden');
    expect(await screen.findByRole('heading', { name: 'Forbidden' })).toBeInTheDocument();
    expect(signinRedirect).not.toHaveBeenCalled();
  });

  describe('merchant approval states', () => {
    const applicant = (merchantStatus?: string): Who => ({
      label: `CUSTOMER applicant ${merchantStatus ?? 'without application'}`, roles: ['CUSTOMER'],
      profile: { accountStatus: 'ACTIVE', ...(merchantStatus ? { merchantStatus } : {}) },
    });
    const merchantWith = (merchantStatus?: string): Who => ({
      label: `MERCHANT ${merchantStatus ?? 'without approval record'}`, roles: ['MERCHANT'],
      profile: { accountStatus: 'ACTIVE', ...(merchantStatus ? { merchantStatus } : {}) },
    });
    const withApplication = (status: string) => server.use(http.get('*/api/v1/merchants/me', () =>
      HttpResponse.json({ id: 'application-1', businessName: 'Manual Store', status })));

    it.each([
      ['/merchant/dashboard', 'open'],
      ['/merchant/dashboard/webhooks', 'forbidden'],
      ['/merchant/dashboard/api-credentials', 'forbidden'],
    ] as const)('a PENDING applicant on %s -> %s', async (path, outcome) => {
      signIn(applicant('PENDING'));
      renderApp(path);
      if (outcome === 'open') {
        expect(await screen.findByRole('heading', { name: /reviewing your details/i }, { timeout: 10000 })).toBeInTheDocument();
        expect(location()).toBe(path);
      } else {
        await expectOutcome(path, 'forbidden');
      }
    }, 30000);

    it.each([['PENDING', /Your application is/i], ['REJECTED', /A little more information/i]] as const)(
      'a %s applicant sees the application status page', async (status, heading) => {
        signIn(applicant(status));
        withApplication(status);
        renderApp('/merchant/status');
        expect(await screen.findByRole('heading', { level: 1, name: heading }, { timeout: 10000 })).toBeInTheDocument();
        expect(location()).toBe('/merchant/status');
      }, 30000);

    it('an applicant with an application is moved from register-business to the status page', async () => {
      signIn(applicant('PENDING'));
      withApplication('PENDING');
      renderApp('/merchant/register-business');
      await waitFor(() => expect(location()).toBe('/merchant/status'), { timeout: 10000 });
    }, 30000);

    it.each([['REJECTED'], [undefined]] as const)('a %s applicant cannot open the merchant dashboard', async (status) => {
      signIn(applicant(status));
      renderApp('/merchant/dashboard');
      await expectOutcome('/merchant/dashboard', 'forbidden');
    }, 30000);

    it.each([['PENDING'], ['REJECTED'], [undefined]] as const)(
      'a MERCHANT whose approval is %s cannot open any merchant page', async (status) => {
        for (const path of ['/merchant/dashboard', '/merchant/dashboard/webhooks']) {
          signIn(merchantWith(status));
          const { unmount } = renderApp(path);
          await expectOutcome(path, 'forbidden');
          unmount();
          queryClient.clear();
        }
      }, 30000);

    it.each(['/merchant/dashboard/webhooks', '/merchant/dashboard/api-credentials'])(
      'an approved MERCHANT opens %s', async (path) => {
        signIn(merchant);
        renderApp(path);
        expect(await screen.findByRole('navigation', { name: 'Merchant navigation' }, { timeout: 10000 })).toBeInTheDocument();
        expect(location()).toBe(path);
      }, 30000);

    it('an approved MERCHANT sees the approval check while the profile loads, then the dashboard', async () => {
      signIn(merchant);
      server.use(http.get('*/api/v1/users/me', async () => {
        await delay(300);
        return HttpResponse.json({ id: 'merchant', roles: ['MERCHANT'], accountStatus: 'ACTIVE', merchantStatus: 'APPROVED' });
      }));
      renderApp('/merchant/dashboard');
      expect(await screen.findByRole('status', { name: 'Checking merchant approval' })).toBeInTheDocument();
      expect(await screen.findByRole('navigation', { name: 'Merchant navigation' }, { timeout: 10000 })).toBeInTheDocument();
      expect(location()).toBe('/merchant/dashboard');
    }, 30000);

    it('a PENDING applicant reloading the dashboard waits for the profile instead of being forbidden', async () => {
      signIn(applicant('PENDING'));
      server.use(http.get('*/api/v1/users/me', async () => {
        await delay(300);
        return HttpResponse.json({ id: 'applicant', roles: ['CUSTOMER'], accountStatus: 'ACTIVE', merchantStatus: 'PENDING' });
      }));
      renderApp('/merchant/dashboard');
      expect(await screen.findByRole('status', { name: 'Checking merchant approval' })).toBeInTheDocument();
      expect(await screen.findByRole('heading', { name: /reviewing your details/i }, { timeout: 10000 })).toBeInTheDocument();
      expect(location()).toBe('/merchant/dashboard');
    }, 30000);

    it('a MERCHANT is denied when the profile cannot be loaded (fails closed)', async () => {
      signIn(merchant);
      server.use(http.get('*/api/v1/users/me', () => HttpResponse.json({ title: 'Unavailable' }, { status: 503 })));
      renderApp('/merchant/dashboard');
      await expectOutcome('/merchant/dashboard', 'forbidden');
    }, 30000);
  });

  describe('backend permission errors inside an allowed portal', () => {
    // The guard let the user in, but the backend (the security boundary) refuses the data request. The customer
    // portal is left out: its list pages use built-in sample data unless VITE_USE_CUSTOMER_MOCKS=false (finding F10).
    it.each([
      [merchant, '/merchant/dashboard', 'Merchant navigation'],
      [courier, '/courier', 'Courier navigation'],
      [dispatcher, '/dispatcher/users', 'Dispatcher navigation'],
    ] as const)('%#: a %s page answered with 401 or 403 keeps the portal and shows a message', async (who, path, navigation) => {
      for (const status of [401, 403]) {
        signIn(who);
        server.use(http.all('*/api/*', ({ request }) => (request.url.includes('/api/v1/users/me')
          ? HttpResponse.json({ id: who.label, roles: who.roles, ...who.profile })
          : HttpResponse.json({ title: status === 401 ? 'Unauthorized' : 'Forbidden', status }, { status }))));
        const { unmount } = renderApp(path);
        expect(await screen.findByRole('navigation', { name: navigation }, { timeout: 15000 })).toBeInTheDocument();
        const message = status === 401 ? /session has expired/i : /permission|don't have access/i;
        expect(await screen.findByText(message, {}, { timeout: 15000 })).toBeInTheDocument();
        expect(location()).toBe(path);
        expect(signinRedirect).not.toHaveBeenCalled();
        unmount();
        queryClient.clear();
      }
    }, 60000);
  });

  it('admin paths never open an Admin portal: a dispatcher lands in the Dispatcher portal', async () => {
    signIn(dispatcher);
    renderApp('/admin');
    await waitFor(() => expect(location()).toBe('/dispatcher'), { timeout: 10000 });
    expect(screen.queryByText(/admin portal/i)).not.toBeInTheDocument();
  }, 30000);
});
