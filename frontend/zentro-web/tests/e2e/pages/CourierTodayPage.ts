import { expect, type Page } from '@playwright/test';

export class CourierTodayPage {
  constructor(readonly page: Page) {}

  async goto() {
    await this.page.goto('/courier');
    await expect(this.page.getByRole('heading', { name: 'My assigned deliveries' })).toBeVisible();
    await expect(this.page.getByText('Loading deliveries', { exact: false })).toHaveCount(0);
  }

  delivery(parcelId: string) {
    return this.page.getByRole('listitem').filter({ hasText: parcelId });
  }

  async openDetail(parcelId: string) {
    await this.page.goto(`/courier/deliveries/${parcelId}`);
    await expect(this.page.getByRole('heading', { name: 'Delivery details' })).toBeVisible();
  }
}
