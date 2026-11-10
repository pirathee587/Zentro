import { test, expect } from '../fixtures/auth.fixture';
import { ReceiptsPage } from '../pages/ReceiptsPage';

test.describe('Customer Receipts E2E', () => {
  test('Delivered receipts list and receipt paper preview', async ({ authenticatedPage }) => {
    const receiptsPage = new ReceiptsPage(authenticatedPage);

    await receiptsPage.goto();

    // Verify delivered receipts list is populated
    await expect(receiptsPage.receiptList).toBeVisible();
    await expect(receiptsPage.receiptRows.first()).toBeVisible();

    // Verify paper preview is displayed
    await receiptsPage.expectReceiptPaperVisible();
    await expect(receiptsPage.receiptPaper).toContainText('SwiftDrop');
    await expect(receiptsPage.receiptPaper).toContainText('Delivery receipt');
    await expect(receiptsPage.receiptPaper).toContainText('Payment method');
    await expect(receiptsPage.receiptPaper).toContainText('Base fee');
    await expect(receiptsPage.receiptPaper).toContainText('Total');
  });

  test('Selecting a specific delivered parcel updates the receipt view', async ({
    authenticatedPage,
  }) => {
    const receiptsPage = new ReceiptsPage(authenticatedPage);

    // Initial mock delivered parcel: SD-20377
    await receiptsPage.goto('SD-20377');
    await receiptsPage.expectReceiptPaperVisible('SD-20377');
  });

  test('Shows unavailable warning when attempting to view receipt for an in-progress parcel', async ({
    authenticatedPage,
  }) => {
    const receiptsPage = new ReceiptsPage(authenticatedPage);

    // Initial mock in-progress parcel: SD-20418
    await receiptsPage.goto('SD-20418');
    await receiptsPage.expectPendingNotice('SD-20418');

    // Should include a link to track the in-progress parcel
    const trackLink = authenticatedPage.locator('a', { hasText: 'Track parcel' });
    await expect(trackLink).toBeVisible();
  });

  test('Shows not found state when accessing receipt with invalid reference', async ({
    authenticatedPage,
  }) => {
    const receiptsPage = new ReceiptsPage(authenticatedPage);

    await receiptsPage.goto('ZT-NONEXISTENT999');
    await receiptsPage.expectNotFoundNotice('ZT-NONEXISTENT999');
  });
});
