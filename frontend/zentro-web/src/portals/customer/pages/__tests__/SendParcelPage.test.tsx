import React from 'react';
import fs from 'node:fs';
import path from 'node:path';
import { describe, expect, it, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { http, HttpResponse } from 'msw';
import { server } from '@/shared/testing/mocks/server';
import { setAccessToken } from '@/shared/api/apiClient';
import { SendParcelPage } from '../SendParcelPage';
import * as customerApi from '../../services/customerApi';
import {
  BookingError,
  setCustomerApiConfig,
  type FeeEstimateResponse,
  type ParcelBookingResponse,
} from '../../services/customerApi';

const mockFeeEstimate: FeeEstimateResponse = {
  fee: 800,
  formattedFee: 'LKR 800',
  currency: 'LKR',
  estimatedDays: 2,
  breakdown: {
    baseFee: 550,
    weightFee: 250,
    distanceSurcharge: 0,
  },
};

const mockCreatedParcel: ParcelBookingResponse = {
  trackingRef: 'ZT-7K3QX9M2PA',
  status: 'CREATED',
  fee: 800,
  currency: 'LKR',
  estimatedDays: 2,
  createdAt: '2026-10-27T10:00:00.000Z',
  sender: {
    name: 'Sender Contact',
    phone: '0771234567',
    address: '123 Galle Road, Colombo 03',
  },
  recipient: {
    name: 'Kasun Perera',
    phone: '0719876543',
    address: '45 Peradeniya Road, Kandy',
  },
  parcel: {
    weightKg: 2,
    category: 'Medium Box',
    size: 'MEDIUM',
  },
};

async function fillValidForm() {
  fireEvent.change(screen.getByLabelText(/Pickup Address/i), {
    target: { value: '123 Galle Road, Colombo 03' },
  });
  fireEvent.change(screen.getByLabelText(/Pickup Contact Phone/i), {
    target: { value: '0771234567' },
  });
  fireEvent.change(screen.getByLabelText(/Drop-off Address/i), {
    target: { value: '45 Peradeniya Road, Kandy' },
  });
  fireEvent.change(screen.getByLabelText(/Recipient Name/i), {
    target: { value: 'Kasun Perera' },
  });
  fireEvent.change(screen.getByLabelText(/Recipient Phone/i), {
    target: { value: '0719876543' },
  });
  fireEvent.change(screen.getByLabelText(/Weight \(kg\)/i), {
    target: { value: '2' },
  });
}

describe('SendParcelPage Component', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  afterEach(() => {
    setAccessToken(undefined);
    setCustomerApiConfig({
      mode: 'mock',
      useMocks: true,
      baseUrl: '/api/v1/customer',
      parcelsPath: '/api/v1/parcels',
      deliveriesEndpoint: undefined,
    });
  });

  it('valid submission in real mode completes 3-step flow (Estimate -> Review -> Booked) with exact headers and body', async () => {
    setCustomerApiConfig({ mode: 'real' });
    setAccessToken('jwt-real-customer-56');

    let estimateUrl = '';
    let estimateMethod = '';
    let estimateAuth: string | null = null;
    let estimateBody: Record<string, unknown> | null = null;

    let bookingUrl = '';
    let bookingMethod = '';
    let bookingAuth: string | null = null;
    let bookingIdempotencyKey: string | null = null;
    let bookingBody: Record<string, unknown> | null = null;

    server.use(
      http.post('*/api/v1/parcels/estimate', async ({ request }) => {
        estimateUrl = request.url;
        estimateMethod = request.method;
        estimateAuth = request.headers.get('authorization');
        estimateBody = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(mockFeeEstimate);
      }),
      http.post('*/api/v1/parcels', async ({ request }) => {
        bookingUrl = request.url;
        bookingMethod = request.method;
        bookingAuth = request.headers.get('authorization');
        bookingIdempotencyKey = request.headers.get('idempotency-key');
        bookingBody = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(mockCreatedParcel, {
          status: 201,
          headers: { Location: '/api/v1/parcels/ZT-7K3QX9M2PA' },
        });
      })
    );

    render(
      <MemoryRouter>
        <SendParcelPage />
      </MemoryRouter>
    );

    // Initial state: Step 1 active
    expect(screen.getByText('1 Details', { selector: '.sd-step-pill' })).toHaveClass('is-active');

    await fillValidForm();

    // Debounced estimate triggers
    await waitFor(() => {
      expect(screen.getByText('Total fee')).toBeInTheDocument();
      expect(
        screen.getByText('LKR 800', { selector: '.sd-fee-row.is-total span:last-child' })
      ).toBeInTheDocument();
    });

    expect(estimateUrl).toBe('http://localhost:8080/api/v1/parcels/estimate');
    expect(estimateMethod).toBe('POST');
    expect(estimateAuth).toBe('Bearer jwt-real-customer-56');
    expect(estimateBody).toEqual({
      weightKg: 2,
      destinationType: 'SAME_CITY',
      parcelSize: 'MEDIUM',
    });

    const reviewBtn = screen.getByRole('button', { name: /Review & see fee/i });
    expect(reviewBtn).not.toBeDisabled();
    fireEvent.click(reviewBtn);

    // Step 2: Review
    expect(await screen.findByText('Review Your Delivery')).toBeInTheDocument();
    expect(screen.getByText('2 Review', { selector: '.sd-step-pill' })).toHaveClass('is-active');
    expect(screen.getByText(/123 Galle Road/i)).toBeInTheDocument();
    expect(screen.getByText(/Kasun Perera/i)).toBeInTheDocument();

    // Confirm delivery
    const confirmBtn = screen.getByRole('button', { name: /Confirm delivery/i });
    fireEvent.click(confirmBtn);

    // Step 3: Booked
    expect(await screen.findByText('Delivery Confirmed!')).toBeInTheDocument();
    expect(screen.getByText('3 Booked', { selector: '.sd-step-pill' })).toHaveClass('is-active');
    expect(screen.getByText('ZT-7K3QX9M2PA')).toBeInTheDocument();
    expect(screen.getByText(/Confirmed fee: LKR 800/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Track parcel/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Send another/i })).toBeInTheDocument();

    expect(bookingUrl).toBe('http://localhost:8080/api/v1/parcels');
    expect(bookingMethod).toBe('POST');
    expect(bookingAuth).toBe('Bearer jwt-real-customer-56');
    expect(bookingIdempotencyKey).toBeTruthy();
    expect(bookingIdempotencyKey!.length).toBeGreaterThanOrEqual(8);
    expect(bookingBody).toEqual({
      sender: {
        name: 'Sender Contact',
        phone: '0771234567',
        address: '123 Galle Road, Colombo 03',
      },
      recipient: {
        name: 'Kasun Perera',
        phone: '0719876543',
        address: '45 Peradeniya Road, Kandy',
      },
      parcel: {
        category: 'Medium Box',
        weightKg: 2,
        size: 'MEDIUM',
      },
      destinationType: 'SAME_CITY',
    });
    expect(bookingBody).not.toHaveProperty('fee');
    expect(bookingBody).not.toHaveProperty('status');
    expect(bookingBody).not.toHaveProperty('trackingRef');
    expect(bookingBody).not.toHaveProperty('customer');
  });

  it('treats 200 OK idempotent replay the same as 201 Created and shows server fee mismatch notice when fees differ', async () => {
    setCustomerApiConfig({ mode: 'real' });
    setAccessToken('jwt-real-customer-replay');

    server.use(
      http.post('*/api/v1/parcels/estimate', () => HttpResponse.json(mockFeeEstimate)),
      http.post('*/api/v1/parcels', () =>
        HttpResponse.json(
          {
            ...mockCreatedParcel,
            trackingRef: 'ZT-9R4MP2W8XK',
            fee: 950,
          },
          { status: 200 }
        )
      )
    );

    render(
      <MemoryRouter>
        <SendParcelPage />
      </MemoryRouter>
    );

    await fillValidForm();
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /Review & see fee/i })).not.toBeDisabled()
    );
    fireEvent.click(screen.getByRole('button', { name: /Review & see fee/i }));

    expect(await screen.findByText('Review Your Delivery')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Confirm delivery/i }));

    expect(await screen.findByText('Delivery Confirmed!')).toBeInTheDocument();
    expect(screen.getByText('ZT-9R4MP2W8XK')).toBeInTheDocument();
    expect(screen.getByText(/Confirmed fee: LKR 950/i)).toBeInTheDocument();
    expect(
      screen.getByRole('status')
    ).toHaveTextContent(/The confirmed fee \(LKR 950\) differs from the earlier quote \(LKR 800\)/i);
  });

  it('validates each required/invalid field (including max-length limits) and sends no booking request', async () => {
    const createSpy = vi.spyOn(customerApi, 'createDelivery');

    render(
      <MemoryRouter>
        <SendParcelPage />
      </MemoryRouter>
    );

    // 1. Pickup address too short (< 8 chars) and too long (> 255 chars)
    const pickupInput = screen.getByLabelText(/Pickup Address/i);
    fireEvent.change(pickupInput, { target: { value: 'Short' } });
    fireEvent.blur(pickupInput);
    expect(await screen.findByText(/Enter at least 8 characters/i)).toBeInTheDocument();

    fireEvent.change(pickupInput, { target: { value: 'A'.repeat(256) } });
    fireEvent.blur(pickupInput);
    expect(
      await screen.findByText(/Address must be between 8 and 255 characters/i)
    ).toBeInTheDocument();

    // 2. Invalid pickup phone format
    const phoneInput = screen.getByLabelText(/Pickup Contact Phone/i);
    fireEvent.change(phoneInput, { target: { value: '12345' } });
    fireEvent.blur(phoneInput);
    expect(await screen.findByText(/Enter a Sri Lankan number/i)).toBeInTheDocument();

    // 3. Drop-off address too short and too long (> 255 chars)
    const dropoffInput = screen.getByLabelText(/Drop-off Address/i);
    fireEvent.change(dropoffInput, { target: { value: 'Near' } });
    fireEvent.blur(dropoffInput);
    expect(await screen.findByText(/Enter at least 8 characters/i)).toBeInTheDocument();

    fireEvent.change(dropoffInput, { target: { value: 'B'.repeat(256) } });
    fireEvent.blur(dropoffInput);
    expect(screen.getAllByText(/Address must be between 8 and 255 characters/i).length).toBe(2);

    // 4. Missing recipient name and recipient name > 100 chars
    const recipientInput = screen.getByLabelText(/Recipient Name/i);
    fireEvent.change(recipientInput, { target: { value: ' ' } });
    fireEvent.blur(recipientInput);
    expect(await screen.findByText(/Recipient name is required/i)).toBeInTheDocument();

    fireEvent.change(recipientInput, { target: { value: 'N'.repeat(101) } });
    fireEvent.blur(recipientInput);
    expect(
      await screen.findByText(/Recipient name must be at most 100 characters/i)
    ).toBeInTheDocument();

    // 5. Invalid recipient phone format
    const recipientPhoneInput = screen.getByLabelText(/Recipient Phone/i);
    fireEvent.change(recipientPhoneInput, { target: { value: 'bad-phone' } });
    fireEvent.blur(recipientPhoneInput);
    expect(screen.getAllByText(/Enter a Sri Lankan number/i).length).toBe(2);

    // 6. Weight out of range
    const weightInput = screen.getByLabelText(/Weight \(kg\)/i);
    fireEvent.change(weightInput, { target: { value: '25' } });
    fireEvent.blur(weightInput);
    expect(await screen.findByText(/Weight must be between 0.1 and 20 kg/i)).toBeInTheDocument();

    // "Review & see fee" button remains disabled and no booking request is sent
    const reviewBtn = screen.getByRole('button', { name: /Review & see fee/i });
    expect(reviewBtn).toBeDisabled();
    expect(createSpy).not.toHaveBeenCalled();
  });

  it('clears quote, disables review until fresh quote succeeds, and rotates idempotency key when editing any field', async () => {
    let resolveSecondQuote: ((val: FeeEstimateResponse) => void) | null = null;
    vi.spyOn(customerApi, 'estimateFee')
      .mockResolvedValueOnce(mockFeeEstimate)
      .mockImplementationOnce(
        () =>
          new Promise<FeeEstimateResponse>((resolve) => {
            resolveSecondQuote = resolve;
          })
      );

    const createSpy = vi
      .spyOn(customerApi, 'createDelivery')
      .mockRejectedValueOnce(
        new BookingError('unavailable', 'Booking service is temporarily unavailable.', {}, 503)
      )
      .mockResolvedValueOnce(mockCreatedParcel);

    render(
      <MemoryRouter>
        <SendParcelPage />
      </MemoryRouter>
    );

    await fillValidForm();
    const reviewBtn = screen.getByRole('button', { name: /Review & see fee/i });
    await waitFor(() => expect(reviewBtn).not.toBeDisabled());

    // Proceed to Step 2 and fail once to capture first idempotency key
    fireEvent.click(reviewBtn);
    expect(await screen.findByText('Review Your Delivery')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Confirm delivery/i }));
    expect(await screen.findByText(/Booking service is temporarily unavailable/i)).toBeInTheDocument();
    const firstKey = createSpy.mock.calls[0][1]?.idempotencyKey;
    expect(firstKey).toBeDefined();

    // Go back to Step 1 and edit recipient name (non-pricing field)
    fireEvent.click(screen.getByRole('button', { name: /Back/i }));
    fireEvent.change(screen.getByLabelText(/Recipient Name/i), {
      target: { value: 'Nimal Jayasuriya' },
    });

    // Quote is cleared immediately and Review button is disabled while fresh quote is pending
    expect(screen.getByRole('button', { name: /Review & see fee/i })).toBeDisabled();
    expect(screen.queryByText('Total fee')).not.toBeInTheDocument();

    // Wait for debounce to invoke second estimate
    await waitFor(() => expect(resolveSecondQuote).not.toBeNull());
    expect(screen.getByRole('button', { name: /Review & see fee/i })).toBeDisabled();

    // Resolve fresh quote -> Review button re-enables
    resolveSecondQuote!(mockFeeEstimate);
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /Review & see fee/i })).not.toBeDisabled()
    );

    fireEvent.click(screen.getByRole('button', { name: /Review & see fee/i }));
    fireEvent.click(screen.getByRole('button', { name: /Confirm delivery/i }));
    expect(await screen.findByText('Delivery Confirmed!')).toBeInTheDocument();

    const secondKey = createSpy.mock.calls[1][1]?.idempotencyKey;
    expect(secondKey).toBeDefined();
    expect(secondKey).not.toBe(firstKey);
  });

  it('sends only one booking request when Confirm delivery is double-clicked while in flight', async () => {
    vi.spyOn(customerApi, 'estimateFee').mockResolvedValue(mockFeeEstimate);
    let resolveBooking: ((val: ParcelBookingResponse) => void) | null = null;
    const createSpy = vi.spyOn(customerApi, 'createDelivery').mockImplementation(
      () =>
        new Promise<ParcelBookingResponse>((resolve) => {
          resolveBooking = resolve;
        })
    );

    render(
      <MemoryRouter>
        <SendParcelPage />
      </MemoryRouter>
    );

    await fillValidForm();
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /Review & see fee/i })).not.toBeDisabled()
    );
    fireEvent.click(screen.getByRole('button', { name: /Review & see fee/i }));

    const confirmBtn = await screen.findByRole('button', { name: /Confirm delivery/i });
    // Double-click synchronously
    fireEvent.click(confirmBtn);
    fireEvent.click(confirmBtn);

    expect(createSpy).toHaveBeenCalledTimes(1);
    expect(screen.getByRole('button', { name: /Booking\.\.\./i })).toBeDisabled();

    resolveBooking!(mockCreatedParcel);
    expect(await screen.findByText('Delivery Confirmed!')).toBeInTheDocument();
    expect(createSpy).toHaveBeenCalledTimes(1);
  });

  it('maps server 422 errors back to step 1 under matching fields and shows generic banner for unknown keys', async () => {
    vi.spyOn(customerApi, 'estimateFee').mockResolvedValue(mockFeeEstimate);
    const createSpy = vi
      .spyOn(customerApi, 'createDelivery')
      .mockRejectedValueOnce(
        new BookingError(
          'validation',
          'One or more booking fields are invalid.',
          {
            'recipient.phone': 'Provided number is not reachable by SMS dispatch.',
            'sender.address': 'Pickup address must be at least 8 characters.',
          },
          422
        )
      )
      .mockRejectedValueOnce(
        new BookingError(
          'validation',
          'Internal validator detail',
          { 'internal.unmappedPath': 'Raw server stack field error' },
          422
        )
      );

    render(
      <MemoryRouter>
        <SendParcelPage />
      </MemoryRouter>
    );

    await fillValidForm();
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /Review & see fee/i })).not.toBeDisabled()
    );
    fireEvent.click(screen.getByRole('button', { name: /Review & see fee/i }));

    // 1st attempt: known field keys mapped to Step 1 inputs
    expect(await screen.findByText('Review Your Delivery')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Confirm delivery/i }));

    expect(await screen.findByText('1 Details', { selector: '.sd-step-pill' })).toHaveClass(
      'is-active'
    );
    expect(
      screen.getByText('Provided number is not reachable by SMS dispatch.')
    ).toBeInTheDocument();
    expect(
      screen.getByText('Pickup address must be at least 8 characters.')
    ).toBeInTheDocument();

    // Fix fields to proceed again and test unknown 422 key
    fireEvent.change(screen.getByLabelText(/Pickup Address/i), {
      target: { value: '125 Galle Road, Colombo 03' },
    });
    fireEvent.change(screen.getByLabelText(/Recipient Phone/i), {
      target: { value: '0719876544' },
    });
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /Review & see fee/i })).not.toBeDisabled()
    );
    fireEvent.click(screen.getByRole('button', { name: /Review & see fee/i }));
    fireEvent.click(await screen.findByRole('button', { name: /Confirm delivery/i }));

    expect(await screen.findByText('1 Details', { selector: '.sd-step-pill' })).toHaveClass(
      'is-active'
    );
    expect(
      screen.getByText('Some booking details are missing or invalid. Please check your details.')
    ).toBeInTheDocument();
    expect(screen.queryByText(/Raw server stack field error/i)).not.toBeInTheDocument();
    expect(createSpy).toHaveBeenCalledTimes(2);
  });

  it('handles 400, 403 (no retry), and 409 (rotates idempotency key) distinctly on Step 2', async () => {
    vi.spyOn(customerApi, 'estimateFee').mockResolvedValue(mockFeeEstimate);
    const createSpy = vi
      .spyOn(customerApi, 'createDelivery')
      .mockRejectedValueOnce(
        new BookingError(
          'bad_request',
          "We couldn't process this request, please check your details",
          {},
          400
        )
      )
      .mockRejectedValueOnce(
        new BookingError(
          'conflict',
          'This booking conflicts with an earlier attempt. Review your details and try again',
          {},
          409
        )
      )
      .mockRejectedValueOnce(
        new BookingError('forbidden', "Your account can't create deliveries", {}, 403)
      );

    render(
      <MemoryRouter>
        <SendParcelPage />
      </MemoryRouter>
    );

    await fillValidForm();
    await waitFor(() =>
      expect(screen.getByRole('button', { name: /Review & see fee/i })).not.toBeDisabled()
    );
    fireEvent.click(screen.getByRole('button', { name: /Review & see fee/i }));

    // 1. 400 Bad Request
    fireEvent.click(await screen.findByRole('button', { name: /Confirm delivery/i }));
    expect(
      await screen.findByText("We couldn't process this request, please check your details")
    ).toBeInTheDocument();
    const keyAttempt1 = createSpy.mock.calls[0][1]?.idempotencyKey;

    // 2. 409 Conflict -> shows conflict message and rotates key for the next attempt
    fireEvent.click(screen.getByRole('button', { name: /Confirm delivery/i }));
    expect(
      await screen.findByText(
        'This booking conflicts with an earlier attempt. Review your details and try again'
      )
    ).toBeInTheDocument();
    const keyAttempt2 = createSpy.mock.calls[1][1]?.idempotencyKey;
    expect(keyAttempt2).toBe(keyAttempt1);

    // 3. Next attempt after 409 uses rotated key; returns 403 Forbidden (no retry, confirm disabled)
    fireEvent.click(screen.getByRole('button', { name: /Confirm delivery/i }));
    expect(
      await screen.findByText("Your account can't create deliveries")
    ).toBeInTheDocument();
    const keyAttempt3 = createSpy.mock.calls[2][1]?.idempotencyKey;
    expect(keyAttempt3).toBeDefined();
    expect(keyAttempt3).not.toBe(keyAttempt2);

    expect(screen.getByRole('button', { name: /Confirm delivery/i })).toBeDisabled();
    expect(screen.queryByRole('button', { name: /Retry booking/i })).not.toBeInTheDocument();
  });

  it('handles estimate failure and enables retry', async () => {
    const estimateSpy = vi
      .spyOn(customerApi, 'estimateFee')
      .mockRejectedValueOnce(new Error('Pricing engine timeout'))
      .mockResolvedValueOnce(mockFeeEstimate);

    render(
      <MemoryRouter>
        <SendParcelPage />
      </MemoryRouter>
    );

    await fillValidForm();

    // Error state in fee card
    expect(await screen.findByText(/Unable to calculate fee. Please tap retry./i)).toBeInTheDocument();
    const reviewBtn = screen.getByRole('button', { name: /Review & see fee/i });
    expect(reviewBtn).toBeDisabled();

    // Retry estimate
    const retryBtn = screen.getByRole('button', { name: /Retry estimate/i });
    fireEvent.click(retryBtn);

    // Success state
    expect(await screen.findByText('Total fee')).toBeInTheDocument();
    expect(screen.getByText('LKR 800', { selector: '.sd-fee-row.is-total span:last-child' })).toBeInTheDocument();
    expect(reviewBtn).not.toBeDisabled();
    expect(estimateSpy).toHaveBeenCalledTimes(2);
  });

  it('handles booking failure (503 and network error) and reuses same idempotency key on retry', async () => {
    vi.spyOn(customerApi, 'estimateFee').mockResolvedValue(mockFeeEstimate);
    const createSpy = vi
      .spyOn(customerApi, 'createDelivery')
      .mockRejectedValueOnce(
        new BookingError('unavailable', 'Booking service is temporarily unavailable. Please try again.', {}, 503)
      )
      .mockRejectedValueOnce(
        new BookingError('unavailable', 'Booking service is temporarily unavailable. Please try again.')
      )
      .mockResolvedValueOnce(mockCreatedParcel);

    render(
      <MemoryRouter>
        <SendParcelPage />
      </MemoryRouter>
    );

    await fillValidForm();
    await waitFor(() => expect(screen.getByRole('button', { name: /Review & see fee/i })).not.toBeDisabled());
    fireEvent.click(screen.getByRole('button', { name: /Review & see fee/i }));

    // First booking attempt fails with 503
    expect(await screen.findByText('Review Your Delivery')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Confirm delivery/i }));

    expect(await screen.findByText(/Booking service is temporarily unavailable/i)).toBeInTheDocument();
    // Form data is preserved in review summary
    expect(screen.getByText(/123 Galle Road/i)).toBeInTheDocument();

    // Click Retry booking (second attempt fails with network error)
    fireEvent.click(screen.getByRole('button', { name: /Retry booking/i }));
    expect(await screen.findByText(/Booking service is temporarily unavailable/i)).toBeInTheDocument();

    // Click Retry booking again -> succeeds
    fireEvent.click(screen.getByRole('button', { name: /Retry booking/i }));

    // Booked successfully
    expect(await screen.findByText('Delivery Confirmed!')).toBeInTheDocument();
    expect(createSpy).toHaveBeenCalledTimes(3);

    // Crucial check: verify all three attempts sent the EXACT SAME idempotency key
    const firstCallKey = createSpy.mock.calls[0][1]?.idempotencyKey;
    const secondCallKey = createSpy.mock.calls[1][1]?.idempotencyKey;
    const thirdCallKey = createSpy.mock.calls[2][1]?.idempotencyKey;
    expect(firstCallKey).toBeDefined();
    expect(firstCallKey).toBe(secondCallKey);
    expect(secondCallKey).toBe(thirdCallKey);
  });

  it('handles 401 session expired by disabling retry and prompting sign in', async () => {
    vi.spyOn(customerApi, 'estimateFee').mockResolvedValue(mockFeeEstimate);
    vi.spyOn(customerApi, 'createDelivery').mockRejectedValue(
      new BookingError('unauthorized', 'Your session has expired. Please sign in again.', {}, 401)
    );

    render(
      <MemoryRouter>
        <SendParcelPage />
      </MemoryRouter>
    );

    await fillValidForm();
    await waitFor(() => expect(screen.getByRole('button', { name: /Review & see fee/i })).not.toBeDisabled());
    fireEvent.click(screen.getByRole('button', { name: /Review & see fee/i }));

    expect(await screen.findByText('Review Your Delivery')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Confirm delivery/i }));

    // Shows session expired alert
    expect(await screen.findByText('Session expired')).toBeInTheDocument();
    expect(screen.getByText(/Your session has expired/i)).toBeInTheDocument();

    // Sign in again button is present, confirm/retry is disabled
    expect(screen.getByRole('button', { name: /Sign in again/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Confirm delivery/i })).toBeDisabled();
    expect(screen.queryByRole('button', { name: /Retry booking/i })).not.toBeInTheDocument();
  });

  it('guard check: no client-side fee arithmetic exists in SendParcelPage.tsx or sendParcelForm.ts', () => {
    const pageSource = fs.readFileSync(path.resolve(__dirname, '../SendParcelPage.tsx'), 'utf8');
    const formSource = fs.readFileSync(path.resolve(__dirname, '../sendParcelForm.ts'), 'utf8');

    expect(pageSource).not.toMatch(/baseFee\s*\+\s*weightFee/);
    expect(pageSource).not.toMatch(/breakdown\.baseFee.*?\+/);
    expect(pageSource).not.toMatch(/\b(550|402|670|724)\b/);
    expect(formSource).not.toMatch(/\b(550|402|670|724)\b/);
  });
});
