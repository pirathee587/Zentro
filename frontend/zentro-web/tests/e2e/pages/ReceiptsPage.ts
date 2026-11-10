import { type Page, type Locator, expect } from '@playwright/test';

export class ReceiptsPage {
  readonly page: Page;
  readonly receiptList: Locator;
  readonly receiptRows: Locator;
  readonly receiptPaper: Locator;
  readonly pendingNotice: Locator;
  readonly notFoundNotice: Locator;
  readonly printButton: Locator;

  constructor(page: Page) {
    this.page = page;
    this.receiptList = page.locator('.sd-receipt-list');
    this.receiptRows = page.locator('.sd-receipt-list-row');
    this.receiptPaper = page.locator('#printable-receipt, .sd-receipt-paper');
    this.pendingNotice = page.locator('.sd-empty-state', { hasText: 'Receipt unavailable' });
    this.notFoundNotice = page.locator('.sd-empty-state', { hasText: 'Delivery receipt not found' });
    this.printButton = page.locator('button', { hasText: 'Print receipt' });
  }

  async goto(trackingRef?: string) {
    if (trackingRef) {
      await this.page.goto(`/customer/receipts?trackingRef=${encodeURIComponent(trackingRef)}`);
    } else {
      await this.page.goto('/customer/receipts');
    }
  }

  async selectReceipt(trackingRef: string) {
    const row = this.receiptRows.filter({ hasText: trackingRef });
    await expect(row).toBeVisible({ timeout: 5000 });
    await row.click();
    await expect(this.receiptPaper).toBeVisible({ timeout: 5000 });
  }

  async expectReceiptPaperVisible(trackingRef?: string) {
    await expect(this.receiptPaper).toBeVisible({ timeout: 6000 });
    if (trackingRef) {
      await expect(this.receiptPaper).toContainText(trackingRef);
    }
  }

  async expectPendingNotice(trackingRef: string) {
    await expect(this.pendingNotice).toBeVisible({ timeout: 5000 });
    await expect(this.pendingNotice).toContainText(trackingRef);
  }

  async expectNotFoundNotice(trackingRef: string) {
    await expect(this.notFoundNotice).toBeVisible({ timeout: 5000 });
    await expect(this.notFoundNotice).toContainText(trackingRef);
  }
}

