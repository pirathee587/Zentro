import { expect, type Page } from '@playwright/test';

export class DispatchQueuePage {
  constructor(readonly page: Page) {}

  get dialog() { return this.page.getByRole('dialog', { name: 'Assign delivery' }); }
  get courier() { return this.dialog.getByLabel('Courier', { exact: true }); }
  get confirm() { return this.dialog.getByRole('button', { name: 'Confirm assignment' }); }

  async goto() {
    await this.page.goto('/dispatcher/queue');
    await expect(this.page.getByRole('heading', { name: 'Dispatch Queue', exact: true })).toBeVisible();
  }

  async openAssignment(reference: string) {
    await this.page.getByRole('button', { name: `Assign ${reference}`, exact: true }).click();
    await expect(this.dialog).toBeVisible();
  }

  async selectCourier(courierId: string) {
    await expect(this.courier).toBeEnabled();
    await this.courier.selectOption(courierId);
    await expect(this.confirm).toBeEnabled();
  }

  async expectCount(count: number) {
    await expect(this.page.getByText(`${count} unassigned deliveries`, { exact: true })).toBeVisible();
  }
}
