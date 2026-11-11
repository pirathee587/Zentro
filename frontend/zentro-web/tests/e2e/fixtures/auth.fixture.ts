import { test as base, type Page } from '@playwright/test';

export interface CustomerUser {
  id: string;
  name: string;
  email: string;
  roles: string[];
}

export const DEFAULT_CUSTOMER: CustomerUser = {
  id: 'customer-e2e-id',
  name: 'Zentro Customer',
  email: 'customer@zentro.test',
  roles: ['CUSTOMER'],
};

export async function setupCustomerAuth(page: Page, user: CustomerUser = DEFAULT_CUSTOMER) {
  await page.route('**/api/v1/users/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: user.id,
        name: user.name,
        email: user.email,
        roles: user.roles,
        accountStatus: 'ACTIVE',
      }),
    });
  });

  await page.addInitScript((userData) => {
    window.sessionStorage.setItem(
      'zentro.e2e-user',
      JSON.stringify({
        expired: false,
        access_token: 'mock-access-token-e2e',
        profile: {
          sub: userData.id,
          name: userData.name,
          email: userData.email,
          realm_access: { roles: userData.roles },
        },
        state: { returnTo: '/customer', entryPoint: 'customer' },
      })
    );
  }, user);
}

export async function setupCourierAuth(page: Page, user: CustomerUser = {
    id: 'courier-e2e-id',
    name: 'Zentro Courier',
    email: 'courier@zentro.test',
    roles: ['COURIER'],
  }) {

  await page.route('**/api/v1/users/me', async (route) => {
    await route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        id: user.id,
        name: user.name,
        roles: user.roles,
        accountStatus: 'ACTIVE',
      }),
    });
  });

  await page.addInitScript((userData) => {
    window.sessionStorage.setItem(
      'zentro.e2e-user',
      JSON.stringify({
        expired: false,
        access_token: 'mock-courier-token-e2e',
        profile: {
          sub: userData.id,
          name: userData.name,
          realm_access: { roles: userData.roles },
        },
        state: { returnTo: '/courier', entryPoint: 'staff' },
      })
    );
  }, user);
}

export async function setupDispatcherAuth(page: Page, user: CustomerUser = {
  id: 'dispatcher-e2e-id', name: 'Zentro Dispatcher',
  email: 'dispatcher@zentro.test', roles: ['DISPATCHER'],
}) {
  await page.route('**/api/v1/users/me', route => route.fulfill({
    json: { ...user, accountStatus: 'ACTIVE' },
  }));
  await page.addInitScript(userData => {
    (window as unknown as { __E2E__?: boolean }).__E2E__ = true;
    window.sessionStorage.setItem('zentro.e2e-user', JSON.stringify({
      expired: false, access_token: 'mock-dispatcher-token-e2e',
      profile: {
        sub: userData.id, name: userData.name, email: userData.email,
        realm_access: { roles: userData.roles },
        permissions: ['DISPATCH_VIEW', 'DISPATCH_ASSIGN'],
      },
      state: { returnTo: '/dispatcher', entryPoint: 'staff' },
    }));
  }, user);
}

export const test = base.extend<{
  authenticatedPage: Page;
}>({
  authenticatedPage: async ({ page }, use) => {
    await page.addInitScript(() => {
      (window as unknown as { __E2E__?: boolean }).__E2E__ = true;
    });
    await setupCustomerAuth(page);
    await use(page);
  },
});

export { expect } from '@playwright/test';
