import { test, expect } from '../fixtures/auth.fixture';
import { TrackingPage } from '../pages/TrackingPage';

test.describe('Delivery Tracking E2E', () => {
  test('Direct tracking view via URL loads parcel timeline and status', async ({
    authenticatedPage,
  }) => {
    const trackingPage = new TrackingPage(authenticatedPage);

    // Initial mock parcel: SD-20418 (In progress)
    await trackingPage.goto('SD-20418');
    await trackingPage.expectParcelLoaded('SD-20418', 'In progress');

    // Verify timeline stages
    await expect(trackingPage.stageItems).toHaveCount(5);
    await expect(trackingPage.routeSubtitle).toContainText('Colombo');
    await expect(trackingPage.progressBar).toBeVisible();
  });

  test('Searching for a tracking number updates view with parcel details', async ({
    authenticatedPage,
  }) => {
    const trackingPage = new TrackingPage(authenticatedPage);

    await trackingPage.goto();
    // Search for SD-20377 (Delivered parcel)
    await trackingPage.search('SD-20377');
    await trackingPage.expectParcelLoaded('SD-20377', 'Delivered');
  });

  test('Tracking details persist on page reload', async ({ authenticatedPage }) => {
    const trackingPage = new TrackingPage(authenticatedPage);

    await trackingPage.goto('SD-20418');
    await trackingPage.expectParcelLoaded('SD-20418', 'In progress');

    // Reload the page
    await authenticatedPage.reload();
    await trackingPage.expectParcelLoaded('SD-20418', 'In progress');
    await expect(trackingPage.stageTimeline).toBeVisible();
  });

  test('Public tracking route /track/:trackingNumber works without login', async ({ page }) => {
    // Unauthenticated page with mock tracking response
    await page.addInitScript(() => {
      (window as unknown as { __E2E__?: boolean }).__E2E__ = true;
    });

    await page.route('**/api/v1/tracking/*', async (route) => {
      await route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          trackingRef: 'ZTR-ABCDEFG234',
          status: 'In transit',
          rawStatus: 'IN_TRANSIT',
          originCity: 'Colombo',
          destinationCity: 'Kandy',
          timeline: [
            { title: 'Created', status: 'CREATED', formattedDate: 'Today' },
            { title: 'Picked up', status: 'PICKED_UP', formattedDate: 'Today' },
            { title: 'In transit', status: 'IN_TRANSIT', formattedDate: 'Today' },
          ],
        }),
      });
    });

    const trackingPage = new TrackingPage(page);

    await trackingPage.gotoPublic('ZTR-ABCDEFG234');
    await trackingPage.expectPublicParcelLoaded('ZTR-ABCDEFG234');
  });

  test('Searching an invalid tracking number displays not found state', async ({
    authenticatedPage,
  }) => {
    const trackingPage = new TrackingPage(authenticatedPage);

    await trackingPage.goto();
    await trackingPage.search('ZT-NONEXISTENT999');
    await trackingPage.expectNotFound();
  });
});
