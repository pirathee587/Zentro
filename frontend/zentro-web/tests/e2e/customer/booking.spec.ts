import { test, expect } from '../fixtures/auth.fixture';
import { BookingPage } from '../pages/BookingPage';
import { TrackingPage } from '../pages/TrackingPage';
import { HistoryPage } from '../pages/HistoryPage';

test.describe('Customer Booking E2E Journey', () => {
  test('Complete Happy Path: Details -> Review -> Booked -> Tracking -> History', async ({
    authenticatedPage,
  }) => {
    const bookingPage = new BookingPage(authenticatedPage);
    const trackingPage = new TrackingPage(authenticatedPage);
    const historyPage = new HistoryPage(authenticatedPage);

    // 1. Navigate to Send Parcel page
    await bookingPage.goto();

    // 2. Fill in all delivery details
    await bookingPage.fillForm({
      pickupAddress: 'No. 42, Flower Road, Colombo 07',
      pickupPhone: '077 123 4567',
      destinationType: 'SAME_CITY',
      dropoffAddress: 'No. 88, Havelock Road, Colombo 05',
      recipientName: 'Kamal Perera',
      recipientPhone: '071 987 6543',
      parcelSize: 'MEDIUM',
      weightKg: '2.5',
    });

    // 3. Wait for fee calculation
    await bookingPage.waitForFeeEstimate();
    await expect(bookingPage.feeBreakdown).toContainText('Base fee');
    await expect(bookingPage.feeBreakdown).toContainText('Weight charge');

    // 4. Proceed to Review step
    await bookingPage.proceedToReview();

    // Verify Review step summary rows
    await expect(bookingPage.reviewList).toContainText('Flower Road, Colombo 07');
    await expect(bookingPage.reviewList).toContainText('Kamal Perera');
    await expect(bookingPage.reviewList).toContainText('Havelock Road, Colombo 05');
    await expect(bookingPage.reviewList).toContainText('2.5 kg');

    // 5. Confirm Booking and capture assigned tracking reference
    const trackingRef = await bookingPage.confirmBooking();
    expect(trackingRef).toBeTruthy();
    await expect(bookingPage.bookedCard).toContainText('Shipment Booked Successfully');
    await expect(bookingPage.trackingReferenceBox).toHaveText(trackingRef);

    // 6. Navigate to Track Parcel directly from booking success screen
    await bookingPage.trackParcelButton.click();
    await authenticatedPage.waitForURL(`**/customer/track/${encodeURIComponent(trackingRef)}`);
    await trackingPage.expectParcelLoaded(trackingRef, 'Created');

    // 7. Verify the new parcel appears in Delivery History
    await historyPage.goto();
    await historyPage.expectTrackingRefInHistory(trackingRef);
  });

  test('Send another parcel resets the form to Step 1', async ({ authenticatedPage }) => {
    const bookingPage = new BookingPage(authenticatedPage);

    await bookingPage.goto();
    await bookingPage.fillForm({
      pickupAddress: 'No. 10, Station Road, Colombo',
      pickupPhone: '077 333 4444',
      dropoffAddress: 'No. 20, Main Street, Colombo',
      recipientName: 'Sunil Silva',
      recipientPhone: '071 555 6666',
      weightKg: '1.0',
    });

    await bookingPage.waitForFeeEstimate();
    await bookingPage.proceedToReview();
    await bookingPage.confirmBooking();

    // Click "Send another"
    await bookingPage.sendAnotherButton.click();

    // Should return to Step 1 with empty pickup address and phone
    await expect(bookingPage.pickupAddressInput).toHaveValue('');
    await expect(bookingPage.pickupPhoneInput).toHaveValue('');
    await expect(bookingPage.dropoffAddressInput).toHaveValue('');
    await expect(bookingPage.reviewButton).toBeVisible();
  });
});
