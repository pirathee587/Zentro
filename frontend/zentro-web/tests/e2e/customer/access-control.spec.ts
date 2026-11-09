import { test as baseTest, expect } from '@playwright/test';
import { setupCustomerAuth, setupCourierAuth } from '../fixtures/auth.fixture';

baseTest.describe('Customer Access Control & Authentication Guards', () => {
  baseTest.beforeEach(async ({ page }) => {
    await page.addInitScript(() => {
      (window as unknown as { __E2E__?: boolean }).__E2E__ = true;
    });
  });

  baseTest('Unauthenticated access to /customer/send is guarded and redirected', async ({
    page,
  }) => {
    // Navigate without authentication session
    await page.goto('/customer/send');

    // ProtectedRoute triggers login redirect to Keycloak or displays loading
    await expect(
      page
        .locator('text=Redirecting to sign in')
        .or(page.locator('#auth-username'))
        .or(page.locator('#kc-form-login'))
        .or(page.locator('#password'))
        .first()
    ).toBeVisible({ timeout: 10000 });
  });

  baseTest('Unauthenticated access to /customer/history is guarded', async ({ page }) => {
    await page.goto('/customer/history');

    await expect(
      page
        .locator('text=Redirecting to sign in')
        .or(page.locator('#auth-username'))
        .or(page.locator('#kc-form-login'))
        .or(page.locator('#password'))
        .first()
    ).toBeVisible({ timeout: 10000 });
  });

  baseTest('Courier role is forbidden from accessing customer portal', async ({ page }) => {
    await setupCourierAuth(page);

    await page.goto('/customer/send');

    // RoleGuard redirects unauthorized role to /forbidden
    await expect(page).toHaveURL(/.*\/forbidden/);
    await expect(page.locator('h1')).toContainText('Forbidden');
  });

  baseTest('Customer role is forbidden from accessing courier portal', async ({ page }) => {
    await setupCustomerAuth(page);

    await page.goto('/courier/deliveries');

    // RoleGuard redirects customer to /forbidden
    await expect(page).toHaveURL(/.*\/forbidden/);
    await expect(page.locator('h1')).toContainText('Forbidden');
  });
});
