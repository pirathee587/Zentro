import React from 'react';
import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { SendParcelPage } from './SendParcelPage';
import * as customerApi from '../services/customerApi';
import { BookingError, type FeeEstimateResponse, type Parcel } from '../services/customerApi';

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

const mockCreatedParcel: Parcel = {
  id: 'pcl-test-101',
  trackingRef: 'ZTR-SD8822A9K1',
  status: 'Created',
  sender: {
    name: 'Sender Contact',
    phone: '0771234567',
    address: '123 Galle Road, Colombo 03',
    city: 'Colombo',
  },
  recipient: {
    name: 'Kasun Perera',
    phone: '0719876543',
    address: '45 Peradeniya Road, Kandy',
    city: 'Kandy',
  },
  parcel: {
    description: 'Medium Box',
    weightKg: 2,
    category: 'Medium Box',
  },
  fee: 800,
  formattedFee: 'LKR 800',
  createdAt: '2026-10-27T10:00:00.000Z',
  formattedCreatedAt: '27 Oct 2026',
  timeline: [],
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

  it('valid submission completes 3-step flow (Details -> Review -> Booked)', async () => {
    vi.spyOn(customerApi, 'estimateFee').mockResolvedValue(mockFeeEstimate);
    const createSpy = vi.spyOn(customerApi, 'createDelivery').mockResolvedValue(mockCreatedParcel);

    render(
      <MemoryRouter>
        <SendParcelPage />
      </MemoryRouter>
    );

    // Initial state: Step 1 active
    expect(screen.getByText('1 Details', { selector: '.sd-step-pill' })).toHaveClass('is-active');

    // Fill valid fields
    await fillValidForm();

    // Debounced estimate triggers
    await waitFor(() => {
      expect(customerApi.estimateFee).toHaveBeenCalled();
      expect(screen.getByText('Total fee')).toBeInTheDocument();
      expect(screen.getByText('LKR 800', { selector: '.sd-fee-row.is-total span:last-child' })).toBeInTheDocument();
    });

    const reviewBtn = screen.getByRole('button', { name: /Review & see fee/i });
    expect(reviewBtn).not.toBeDisabled();
    fireEvent.click(reviewBtn);

    // Now in Step 2: Review
    expect(await screen.findByText('Review Your Delivery')).toBeInTheDocument();
    expect(screen.getByText('2 Review', { selector: '.sd-step-pill' })).toHaveClass('is-active');
    expect(screen.getByText(/123 Galle Road/i)).toBeInTheDocument();
    expect(screen.getByText(/Kasun Perera/i)).toBeInTheDocument();

    // Confirm delivery
    const confirmBtn = screen.getByRole('button', { name: /Confirm delivery/i });
    fireEvent.click(confirmBtn);

    // In Step 3: Booked
    expect(await screen.findByText('Delivery Confirmed!')).toBeInTheDocument();
    expect(screen.getByText('3 Booked', { selector: '.sd-step-pill' })).toHaveClass('is-active');
    expect(screen.getByText('ZTR-SD8822A9K1')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Track parcel/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Send another/i })).toBeInTheDocument();
    expect(createSpy).toHaveBeenCalledTimes(1);
  });

  it('validates each invalid field and blocks proceeding to review', async () => {
    render(
      <MemoryRouter>
        <SendParcelPage />
      </MemoryRouter>
    );

    // 1. Pickup address too short (< 8 chars)
    const pickupInput = screen.getByLabelText(/Pickup Address/i);
    fireEvent.change(pickupInput, { target: { value: 'Short' } });
    fireEvent.blur(pickupInput);
    expect(await screen.findByText(/Enter at least 8 characters/i)).toBeInTheDocument();

    // 2. Invalid phone format
    const phoneInput = screen.getByLabelText(/Pickup Contact Phone/i);
    fireEvent.change(phoneInput, { target: { value: '12345' } });
    fireEvent.blur(phoneInput);
    expect(await screen.findByText(/Enter a Sri Lankan number/i)).toBeInTheDocument();

    // 3. Drop-off address too short
    const dropoffInput = screen.getByLabelText(/Drop-off Address/i);
    fireEvent.change(dropoffInput, { target: { value: 'Near' } });
    fireEvent.blur(dropoffInput);
    expect(screen.getAllByText(/Enter at least 8 characters/i).length).toBe(2);

    // 4. Missing recipient name
    const recipientInput = screen.getByLabelText(/Recipient Name/i);
    fireEvent.change(recipientInput, { target: { value: ' ' } });
    fireEvent.blur(recipientInput);
    expect(await screen.findByText(/Recipient name is required/i)).toBeInTheDocument();

    // 5. Weight out of range
    const weightInput = screen.getByLabelText(/Weight \(kg\)/i);
    fireEvent.change(weightInput, { target: { value: '25' } });
    fireEvent.blur(weightInput);
    expect(await screen.findByText(/Weight must be between 0.1 and 20 kg/i)).toBeInTheDocument();

    // "Review & see fee" button remains disabled
    const reviewBtn = screen.getByRole('button', { name: /Review & see fee/i });
    expect(reviewBtn).toBeDisabled();
  });

  it('maps server 422 errors back to step 1 under matching fields', async () => {
    vi.spyOn(customerApi, 'estimateFee').mockResolvedValue(mockFeeEstimate);
    vi.spyOn(customerApi, 'createDelivery').mockRejectedValue(
      new BookingError(
        'validation',
        'Recipient mobile number is inactive.',
        { 'recipient.phone': 'Provided number is not reachable by SMS dispatch.' },
        422
      )
    );

    render(
      <MemoryRouter>
        <SendParcelPage />
      </MemoryRouter>
    );

    await fillValidForm();
    await waitFor(() => expect(screen.getByRole('button', { name: /Review & see fee/i })).not.toBeDisabled());
    fireEvent.click(screen.getByRole('button', { name: /Review & see fee/i }));

    // Click confirm in Step 2
    expect(await screen.findByText('Review Your Delivery')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Confirm delivery/i }));

    // Returns to Step 1 with field-level server error
    expect(await screen.findByText('1 Details', { selector: '.sd-step-pill' })).toHaveClass('is-active');
    expect(screen.getByText('Provided number is not reachable by SMS dispatch.')).toBeInTheDocument();
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

  it('handles booking failure (503/network) and reuses same idempotency key on retry', async () => {
    vi.spyOn(customerApi, 'estimateFee').mockResolvedValue(mockFeeEstimate);
    const createSpy = vi
      .spyOn(customerApi, 'createDelivery')
      .mockRejectedValueOnce(
        new BookingError('unavailable', 'Booking service is temporarily unavailable. Please try again.', {}, 503)
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

    // First booking attempt fails
    expect(await screen.findByText('Review Your Delivery')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /Confirm delivery/i }));

    expect(await screen.findByText(/Booking service is temporarily unavailable/i)).toBeInTheDocument();
    // Form data is preserved in review summary
    expect(screen.getByText(/123 Galle Road/i)).toBeInTheDocument();

    // Click Retry booking
    const retryBookingBtn = screen.getByRole('button', { name: /Retry booking/i });
    fireEvent.click(retryBookingBtn);

    // Booked successfully
    expect(await screen.findByText('Delivery Confirmed!')).toBeInTheDocument();
    expect(createSpy).toHaveBeenCalledTimes(2);

    // Crucial check: verify both attempts sent the EXACT SAME idempotency key
    const firstCallKey = createSpy.mock.calls[0][1]?.idempotencyKey;
    const secondCallKey = createSpy.mock.calls[1][1]?.idempotencyKey;
    expect(firstCallKey).toBeDefined();
    expect(firstCallKey).toBe(secondCallKey);
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
});
