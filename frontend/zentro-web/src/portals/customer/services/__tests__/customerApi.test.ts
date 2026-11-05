import { describe, expect, it, vi, afterEach } from 'vitest';
import { http, HttpResponse } from 'msw';
import { server } from '@/shared/testing/mocks/server';
import { apiClient, setAccessToken } from '@/shared/api/apiClient';
import {
  getDashboard,
  estimateFee,
  createDelivery,
  listParcels,
  getParcel,
  subscribeToParcel,
  listReceipts,
  askAssistant,
  sendSupportMessage,
  normalizeListParcelsResponse,
  setCustomerApiConfig,
  BookingError,
  toBookingError,
} from '../customerApi';

describe('SwiftDrop customerApi service and mock adapter', () => {
  afterEach(() => {
    vi.restoreAllMocks();
    setAccessToken(undefined);
    setCustomerApiConfig({
      mode: 'mock',
      useMocks: true,
      baseUrl: '/api/v1/customer',
      parcelsPath: '/api/v1/parcels',
      deliveriesEndpoint: undefined,
    });
  });

  it('getDashboard returns metrics and recent parcels', async () => {
    const data = await getDashboard();
    expect(data).toBeDefined();
    expect(typeof data.activeCount).toBe('number');
    expect(data.formattedTotalSpent).toContain('LKR');
    expect(Array.isArray(data.recentParcels)).toBe(true);
  });

  it('estimateFee calculates fee without UI computation', async () => {
    const res = await estimateFee({
      pickupCity: 'Colombo 04',
      deliveryCity: 'Kandy',
      weightKg: 2.5,
      isExpress: true,
    });
    expect(res.fee).toBeGreaterThan(550);
    expect(res.formattedFee).toBe(`LKR ${res.fee.toLocaleString('en-US')}`);
    expect(res.estimatedDays).toBe(1);
    expect(res.breakdown.distanceSurcharge).toBeUndefined();
    expect(res.breakdown.expressFee).toBe(300);
  });

  it('estimateFee calls POST /api/v1/parcels/estimate with Bearer token in real mode and keeps other customer endpoints on mock', async () => {
    setCustomerApiConfig({ mode: 'real' });
    setAccessToken('jwt-customer-token-16');

    let capturedUrl = '';
    let capturedAuth: string | null = null;
    let capturedBody: unknown = null;

    server.use(
      http.post('*/api/v1/parcels/estimate', async ({ request }) => {
        capturedUrl = request.url;
        capturedAuth = request.headers.get('authorization');
        capturedBody = await request.json();
        return HttpResponse.json({
          fee: 1072,
          formattedFee: 'LKR 1,072',
          currency: 'LKR',
          estimatedDays: 3,
          breakdown: {
            baseFee: 550,
            weightFee: 120,
            distanceSurcharge: 402,
          },
        });
      })
    );

    const res = await estimateFee({
      weightKg: 2,
      destinationType: 'OTHER_DISTRICT',
      parcelSize: 'MEDIUM',
    });

    expect(capturedUrl).toBe('http://localhost:8080/api/v1/parcels/estimate');
    expect(capturedAuth).toBe('Bearer jwt-customer-token-16');
    expect(capturedBody).toEqual({
      weightKg: 2,
      destinationType: 'OTHER_DISTRICT',
      parcelSize: 'MEDIUM',
    });
    expect(res.fee).toBe(1072);
    expect(res.formattedFee).toBe('LKR 1,072');
    expect(res.breakdown).toEqual({
      baseFee: 550,
      weightFee: 120,
      distanceSurcharge: 402,
    });

    // Unapproved customer endpoints remain on the mock adapter in real mode
    const dash = await getDashboard();
    expect(dash.recentParcels.length).toBeGreaterThan(0);
    const history = await listParcels();
    expect(history.parcels.length).toBeGreaterThan(0);
    const tracked = await getParcel('SD-20418');
    expect(tracked?.trackingRef).toBe('SD-20418');
    const receipts = await listReceipts();
    expect(receipts.length).toBeGreaterThan(0);
    const reply = await askAssistant('Where is my parcel?');
    expect(reply.reply).toBeDefined();
  });

  it('createDelivery in mock mode returns contract-aligned ParcelBookingResponse and supports idempotent replay and 409 conflict', async () => {
    const requestPayload = {
      sender: {
        name: 'Amara',
        phone: '+94 77 123 4567',
        address: '42 Galle Road, Colombo 03',
        city: 'Colombo',
      },
      recipient: {
        name: 'Kamal',
        phone: '+94 71 456 7890',
        address: '10 Main Street, Galle',
        city: 'Galle',
      },
      parcel: {
        description: 'Books',
        weightKg: 1.0,
        category: 'Documents',
        size: 'SMALL' as const,
      },
      destinationType: 'OTHER_DISTRICT' as const,
    };

    const parcel = await createDelivery(requestPayload, { idempotencyKey: 'idem-mock-key-1' });

    expect(parcel.trackingRef).toMatch(/^ZT-[0-9A-HJ-KM-NP-TV-Z]{10}$/);
    expect(parcel.status).toBe('CREATED');
    expect(parcel.currency).toBe('LKR');
    expect(typeof parcel.fee).toBe('number');
    expect('formattedFee' in parcel).toBe(false);
    expect('timeline' in parcel).toBe(false);

    // Same key + same body replays the original booking
    const replayed = await createDelivery(requestPayload, { idempotencyKey: 'idem-mock-key-1' });
    expect(replayed.trackingRef).toBe(parcel.trackingRef);

    // Same key + different body throws 409 conflict
    await expect(
      createDelivery(
        {
          ...requestPayload,
          parcel: { ...requestPayload.parcel, weightKg: 3.5 },
        },
        { idempotencyKey: 'idem-mock-key-1' }
      )
    ).rejects.toMatchObject({
      kind: 'conflict',
      status: 409,
      message: 'This booking conflicts with an earlier attempt. Review your details and try again',
    });

    // Verify demo history/tracking still has it in mock mode
    const fetched = await getParcel(parcel.trackingRef);
    expect(fetched?.trackingRef).toBe(parcel.trackingRef);
  });

  it('createDelivery in real mode posts to /api/v1/parcels with Bearer and Idempotency-Key, strips forbidden fields, and does not mutate mock stores', async () => {
    setCustomerApiConfig({ mode: 'real' });
    setAccessToken('jwt-customer-token-56');

    const beforeHistory = await listParcels();
    const beforeReceipts = await listReceipts();

    let capturedUrl = '';
    let capturedMethod = '';
    let capturedAuth: string | null = null;
    let capturedIdempotencyKey: string | null = null;
    let capturedBody: Record<string, unknown> | null = null;

    server.use(
      http.post('*/api/v1/parcels', async ({ request }) => {
        capturedUrl = request.url;
        capturedMethod = request.method;
        capturedAuth = request.headers.get('authorization');
        capturedIdempotencyKey = request.headers.get('idempotency-key');
        capturedBody = (await request.json()) as Record<string, unknown>;
        return HttpResponse.json(
          {
            trackingRef: 'ZT-7K3QX9M2PA',
            status: 'CREATED',
            fee: 1132,
            currency: 'LKR',
            estimatedDays: 3,
            createdAt: '2026-04-12T10:15:30Z',
            sender: capturedBody.sender,
            recipient: capturedBody.recipient,
            parcel: capturedBody.parcel,
            formattedFee: 'SHOULD_BE_STRIPPED',
            timeline: ['SHOULD_BE_STRIPPED'],
          },
          {
            status: 201,
            headers: { Location: '/api/v1/parcels/ZT-7K3QX9M2PA' },
          }
        );
      })
    );

    const dirtyInput = {
      sender: {
        name: 'Sender Contact',
        phone: '077 123 4567',
        address: '123 Galle Road, Colombo 03',
      },
      recipient: {
        name: 'Nimal Perera',
        phone: '+94 71 987 6543',
        address: '45 Kandy Road, Peradeniya',
      },
      parcel: {
        category: 'Medium Box',
        weightKg: 2.5,
        size: 'MEDIUM' as const,
      },
      destinationType: 'OTHER_DISTRICT' as const,
      fee: 10,
      status: 'DELIVERED',
      trackingRef: 'ZT-FAKE000000',
      customer: 'injected-user',
    };

    const result = await createDelivery(dirtyInput as never, {
      idempotencyKey: '550e8400-e29b-41d4-a716-446655440000',
    });

    expect(capturedUrl).toBe('http://localhost:8080/api/v1/parcels');
    expect(capturedMethod).toBe('POST');
    expect(capturedAuth).toBe('Bearer jwt-customer-token-56');
    expect(capturedIdempotencyKey).toBe('550e8400-e29b-41d4-a716-446655440000');
    expect(capturedBody).toEqual({
      sender: {
        name: 'Sender Contact',
        phone: '077 123 4567',
        address: '123 Galle Road, Colombo 03',
      },
      recipient: {
        name: 'Nimal Perera',
        phone: '+94 71 987 6543',
        address: '45 Kandy Road, Peradeniya',
      },
      parcel: {
        category: 'Medium Box',
        weightKg: 2.5,
        size: 'MEDIUM',
      },
      destinationType: 'OTHER_DISTRICT',
    });
    expect(capturedBody).not.toHaveProperty('fee');
    expect(capturedBody).not.toHaveProperty('status');
    expect(capturedBody).not.toHaveProperty('trackingRef');
    expect(capturedBody).not.toHaveProperty('customer');

    expect(result).toEqual({
      trackingRef: 'ZT-7K3QX9M2PA',
      status: 'CREATED',
      fee: 1132,
      currency: 'LKR',
      estimatedDays: 3,
      createdAt: '2026-04-12T10:15:30Z',
      sender: {
        name: 'Sender Contact',
        phone: '077 123 4567',
        address: '123 Galle Road, Colombo 03',
      },
      recipient: {
        name: 'Nimal Perera',
        phone: '+94 71 987 6543',
        address: '45 Kandy Road, Peradeniya',
      },
      parcel: {
        category: 'Medium Box',
        weightKg: 2.5,
        size: 'MEDIUM',
      },
    });
    expect('formattedFee' in result).toBe(false);
    expect('timeline' in result).toBe(false);

    // Real booking must NOT fake entries in History, Receipts, or Tracking mock stores
    const afterHistory = await listParcels();
    const afterReceipts = await listReceipts();
    const trackedReal = await getParcel('ZT-7K3QX9M2PA');
    expect(afterHistory.total).toBe(beforeHistory.total);
    expect(afterReceipts.length).toBe(beforeReceipts.length);
    expect(trackedReal).toBeNull();
  });

  it('toBookingError maps 400, 401, 403, 409, 422, 503, timeout, network, and unexpected errors distinctly', () => {
    const err422 = toBookingError({
      response: {
        status: 422,
        data: {
          detail: 'One or more booking fields are invalid.',
          fieldErrors: { 'recipient.phone': 'Enter a valid Sri Lankan phone number.' },
        },
      },
    });
    expect(err422).toBeInstanceOf(BookingError);
    expect(err422.kind).toBe('validation');
    expect(err422.fieldErrors).toEqual({
      'recipient.phone': 'Enter a valid Sri Lankan phone number.',
    });

    const err400 = toBookingError({
      response: {
        status: 400,
        data: { detail: 'Idempotency-Key must be between 8 and 128 visible ASCII characters.' },
      },
    });
    expect(err400.kind).toBe('bad_request');
    expect(err400.message).toBe("We couldn't process this request, please check your details");

    const err401 = toBookingError({ response: { status: 401, data: {} } });
    expect(err401.kind).toBe('unauthorized');

    const err403 = toBookingError({ response: { status: 403, data: {} } });
    expect(err403.kind).toBe('forbidden');
    expect(err403.message).toBe("Your account can't create deliveries");

    const err409 = toBookingError({ response: { status: 409, data: {} } });
    expect(err409.kind).toBe('conflict');
    expect(err409.message).toBe(
      'This booking conflicts with an earlier attempt. Review your details and try again'
    );

    const err503 = toBookingError({ response: { status: 503, data: {} } });
    expect(err503.kind).toBe('unavailable');

    const errTimeout = toBookingError({ code: 'ECONNABORTED', message: 'timeout of 10000ms exceeded' });
    expect(errTimeout.kind).toBe('unavailable');

    const errNetwork = toBookingError({ message: 'Network Error' });
    expect(errNetwork.kind).toBe('unavailable');

    const errUnknown = toBookingError({
      response: { status: 418, data: { detail: 'Raw internal stack trace' } },
    });
    expect(errUnknown.kind).toBe('unknown');
    expect(errUnknown.message).toBe('Something went wrong while booking. Please try again.');
  });

  it('listParcels returns list and count using mock adapter', async () => {
    const res = await listParcels();
    expect(res.parcels.length).toBeGreaterThan(0);
    expect(res.total).toBe(res.parcels.length);
  });

  it('listParcels queries real apiClient and normalizes Spring Data Page when useMocks is false', async () => {
    setCustomerApiConfig({ useMocks: false, baseUrl: '/api/v1/customer' });

    const springPageResponse = {
      content: [
        {
          id: 'pcl-srv-1',
          trackingRef: 'ZTR-REAL001',
          status: 'IN_PROGRESS',
          fee: 1200,
          createdAt: '2026-10-25T10:00:00Z',
          sender: { name: 'Sita', city: 'Colombo' },
          recipient: { name: 'Ravi', city: 'Jaffna' },
        },
      ],
      totalElements: 1,
      totalPages: 1,
    };

    const getSpy = vi.spyOn(apiClient, 'get').mockResolvedValueOnce({ data: springPageResponse } as never);

    const res = await listParcels({ page: 1, limit: 10, status: 'In progress' });

    expect(getSpy).toHaveBeenCalledWith('/api/v1/customer/parcels', {
      params: { page: 1, limit: 10, status: 'In progress' },
    });
    expect(res.total).toBe(1);
    expect(res.parcels.length).toBe(1);
    expect(res.parcels[0].trackingRef).toBe('ZTR-REAL001');
    expect(res.parcels[0].status).toBe('In progress');
    expect(res.parcels[0].formattedFee).toBe('LKR 1,200');
  });

  it('listParcels respects custom deliveriesEndpoint when configured', async () => {
    setCustomerApiConfig({
      useMocks: false,
      baseUrl: '/api/v1/customer',
      deliveriesEndpoint: '/api/v1/tracking/mine',
    });

    const getSpy = vi.spyOn(apiClient, 'get').mockResolvedValueOnce({
      data: { parcels: [], total: 0 },
    } as never);

    await listParcels({ page: 1 });

    expect(getSpy).toHaveBeenCalledWith('/api/v1/tracking/mine', {
      params: { page: 1 },
    });
  });

  it('normalizeListParcelsResponse handles plain array and computes totalSpent', () => {
    const rawArray = [
      { id: '1', trackingRef: 'ZTR-1', status: 'DELIVERED', fee: 500 },
      { id: '2', trackingRef: 'ZTR-2', status: 'FAILED', fee: 800 },
    ];

    const normalized = normalizeListParcelsResponse(rawArray);
    expect(normalized.total).toBe(2);
    expect(normalized.parcels.length).toBe(2);
    expect(normalized.parcels[0].status).toBe('Delivered');
    expect(normalized.parcels[1].status).toBe('Failed');
    expect(normalized.totalSpent).toBe(1300);
    expect(normalized.formattedTotalSpent).toBe('LKR 1,300');
  });

  it('normalizeListParcelsResponse handles deliveries or data wrapped responses', () => {
    const wrapped = {
      deliveries: [
        { id: 'd-1', trackingRef: 'ZTR-D1', status: 'In progress', fee: 400 },
      ],
      total: 1,
    };
    const normalized = normalizeListParcelsResponse(wrapped);
    expect(normalized.total).toBe(1);
    expect(normalized.parcels.length).toBe(1);
    expect(normalized.parcels[0].trackingRef).toBe('ZTR-D1');
    expect(normalized.parcels[0].status).toBe('In progress');
  });

  it('subscribeToParcel notifies subscriber and returns unsubscribe function', async () => {
    let notified = false;
    const unsubscribe = subscribeToParcel('ZTR-SD8941K2M7', (p) => {
      if (p.trackingRef === 'ZTR-SD8941K2M7') {
        notified = true;
      }
    });

    await new Promise((r) => setTimeout(r, 50));
    expect(notified).toBe(true);
    expect(typeof unsubscribe).toBe('function');
    unsubscribe();
  });

  it('listReceipts returns official receipts formatted in LKR', async () => {
    const receipts = await listReceipts();
    expect(receipts.length).toBeGreaterThan(0);
    expect(receipts[0].formattedAmount).toContain('LKR');
  });

  it('askAssistant replies with suggested actions', async () => {
    const reply = await askAssistant('How do I track my parcel?');
    expect(reply.reply).toBeDefined();
    expect(reply.suggestedActions?.length).toBeGreaterThan(0);
  });

  it('sendSupportMessage submits support ticket with reference', async () => {
    const ticket = await sendSupportMessage({
      subject: 'Delay question',
      category: 'delivery',
      trackingRef: 'ZTR-SD8941K2M7',
      message: 'When is the parcel arriving in Kandy?',
    });

    expect(ticket.id).toBeDefined();
    expect(ticket.ticketNumber).toContain('TICK-');
    expect(ticket.status).toBe('Open');
  });
});
