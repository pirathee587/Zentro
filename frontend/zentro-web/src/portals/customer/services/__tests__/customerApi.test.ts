import { describe, expect, it, vi, afterEach, beforeEach } from 'vitest';
import { http, HttpResponse } from 'msw';
import { server } from '@/shared/testing/mocks/server';
import { apiClient, setAccessToken } from '@/shared/api/apiClient';
import { estimateFee, createDelivery, listParcels, getParcel, subscribeToParcel, listReceipts,
  askAssistant, sendSupportMessage, normalizeParcelRecord, normalizeListParcelsResponse,
  setCustomerApiConfig, BookingError, toBookingError } from '../customerApi';

const booking = {
  id: 'synthetic-id', trackingRef: 'ZT-SYNTHETIC01', status: 'CREATED', fee: 827.5, currency: 'LKR', estimatedDays: 3,
  createdAt: '2026-11-16T00:00:00Z',
  sender: { name: 'Test sender', phone: '0771234567', address: 'Synthetic pickup address' },
  recipient: { name: 'Test recipient', phone: '0711234567', address: 'Synthetic delivery address' },
  parcel: { weightKg: 2, category: 'Documents', size: 'MEDIUM' as const },
};
const payload = { sender: booking.sender, recipient: booking.recipient, parcel: booking.parcel };
describe('customer API gateway integration without production mock fallbacks', () => {
  beforeEach(() => { setAccessToken('synthetic-jwt'); setCustomerApiConfig({ parcelsPath: '/api/v1/parcels' }); });
  afterEach(() => { vi.restoreAllMocks(); vi.useRealTimers(); setAccessToken(undefined); });
  it('gets the authoritative fee and forwards the customer JWT', async () => {
    server.use(http.post('*/api/v1/parcels/estimate', async ({request}) => {
      expect(request.headers.get('authorization')).toBe('Bearer synthetic-jwt');
      expect(await request.json()).toEqual({weightKg: 2});
      return HttpResponse.json({fee:827.5,currency:'LKR',estimatedDays:3,breakdown:{baseFee:500,weightFee:327.5}});
    }));
    expect((await estimateFee({weightKg:2})).fee).toBe(827.5);
  });
  it('does not manufacture a fee when pricing fails', async () => {
    server.use(http.post('*/api/v1/parcels/estimate',()=>new HttpResponse(null,{status:503})));
    await expect(estimateFee({weightKg:2})).rejects.toBeDefined();
  });
  it('posts booking with stable idempotency key, JWT and only allowed input fields', async () => {
    server.use(http.post('*/api/v1/parcels',async ({request})=>{
      expect(request.headers.get('authorization')).toBe('Bearer synthetic-jwt');
      expect(request.headers.get('idempotency-key')).toBe('stable-attempt');
      expect(await request.json()).toEqual(payload);
      return HttpResponse.json(booking,{status:201});
    }));
    const input={...payload,fee:1,customerSubject:'another-customer',status:'DELIVERED'};
    expect((await createDelivery(input,{idempotencyKey:'stable-attempt'})).trackingRef).toBe(booking.trackingRef);
  });
  it('surfaces server idempotency conflicts without local fake bookings', async () => {
    server.use(http.post('*/api/v1/parcels',()=>new HttpResponse(null,{status:409})));
    await expect(createDelivery(payload,{idempotencyKey:'stable-attempt'})).rejects.toMatchObject({kind:'conflict'});
  });
  it('reads the customer-owned S2 page and maps status/search pagination', async () => {
    server.use(http.get('*/api/v1/parcels/mine',({request})=>{
      const url=new URL(request.url);
      expect(url.searchParams.get('status')).toBe('IN_PROGRESS'); expect(url.searchParams.get('q')).toBe('Kandy');
      expect(url.searchParams.get('size')).toBe('10');
      return HttpResponse.json({content:[booking],totalElements:21});
    }));
    const result=await listParcels({status:'In progress',q:'Kandy',page:2,limit:10});
    expect(result.total).toBe(21); expect(result.parcels[0].fee).toBe(827.5);
  });
  it('does not fall back to fixtures on an authorization failure', async () => {
    server.use(http.get('*/api/v1/parcels/mine',()=>new HttpResponse(null,{status:403})));
    await expect(listParcels()).rejects.toBeDefined();
  });
  it('reads private parcel through S4 GraphQL backed by S2 owner authorization', async () => {
    const delivery={...booking,rawStatus:'CREATED',size:'MEDIUM',description:'',weightKg:2,
      deliveredAt:null,estimatedDeliveryDate:null,failureReason:null,events:[]};
    server.use(http.post('*/graphql',async({request})=>{
      const body=await request.json() as {query:string;variables:{trackingRef:string}};
      expect(body.query).toContain('customerDelivery');expect(body.variables.trackingRef).toBe(booking.trackingRef);
      return HttpResponse.json({data:{customerDelivery:delivery}});
    }));
    expect((await getParcel(booking.trackingRef))?.sender.name).toBe('Test sender');
  });
  it('uses sanitized GraphQL tracking for an anonymous visitor', async () => {
    setAccessToken(undefined);
    server.use(http.post('*/graphql',async ({request})=>{
      const body=await request.json() as {query:string;variables:unknown};
      expect(body.query).not.toMatch(/sender|recipient|fee|phone/);
      return HttpResponse.json({data:{publicTracking:{trackingRef:booking.trackingRef,status:'CREATED',createdAt:booking.createdAt,events:[]}}});
    }));
    const record=await getParcel(booking.trackingRef);
    expect(record?.sender.phone).toBe(''); expect(record?.parcel.weightKg).toBeUndefined();
    expect(record?.fee).toBeUndefined(); expect(record?.sender.city).toBe('');
  });
  it('uses public tracking for a courier browser session instead of the customer-only lookup', async () => {
    server.use(http.post('*/graphql', async ({request})=>{
      const body=await request.json() as {query:string;variables:unknown};
      expect(body.query).toContain('publicTracking');
      expect(body.query).not.toContain('customerDelivery');
      return HttpResponse.json({data:{publicTracking:{trackingRef:booking.trackingRef,status:'CREATED',createdAt:booking.createdAt,events:[]}}});
    }));
    const record=await getParcel(booking.trackingRef, true);
    expect(record?.trackingRef).toBe(booking.trackingRef);
  });
  it('does not bypass a private GraphQL service outage through public tracking', async () => {
    server.use(http.post('*/graphql',()=>HttpResponse.error()));
    await expect(getParcel(booking.trackingRef)).rejects.toBeDefined();
  });
  it('polls authenticated GraphQL tracking and stops after unsubscribe', async () => {
    vi.useFakeTimers();
    const delivery={...booking,rawStatus:'CREATED',size:'MEDIUM',description:'',weightKg:2,
      deliveredAt:null,estimatedDeliveryDate:null,failureReason:null,events:[]};
    const spy=vi.spyOn(apiClient,'post').mockResolvedValue({data:{data:{customerDelivery:delivery}}});
    const callback=vi.fn();const unsubscribe=subscribeToParcel(booking.trackingRef,callback);
    await vi.advanceTimersByTimeAsync(30000);expect(callback).toHaveBeenCalledOnce();unsubscribe();
    await vi.advanceTimersByTimeAsync(60000);expect(spy).toHaveBeenCalledOnce();
  });
  it('reads actual authorized receipts without injecting payment details', async () => {
    server.use(http.get('*/api/v1/parcels/mine/receipts',()=>HttpResponse.json([{receiptNumber:'REC-SYNTHETIC',amount:827.5,paymentStatus:'Unknown',paymentMethod:'Not recorded'}])));
    expect((await listReceipts())[0]).toMatchObject({amount:827.5,paymentStatus:'Unknown',paymentMethod:'Not recorded'});
  });
  it('calls existing S5 assistant with conversation context', async () => {
    server.use(http.post('*/api/v1/assistant/chat',async({request})=>{
      expect(await request.json()).toMatchObject({message:'Where is my parcel?',conversationId:'existing-conversation'});
      return HttpResponse.json({reply:'A verified response',conversationId:'existing-conversation'});
    }));
    expect((await askAssistant('Where is my parcel?',[],'existing-conversation')).reply).toBe('A verified response');
  });
  it('submits support to S5 and displays only returned ticket data', async () => {
    const input={subject:'Delivery issue',category:'delivery' as const,message:'Please check my booking'};
    server.use(http.post('*/api/v1/support/tickets',async({request})=>{
      expect(await request.json()).toEqual(input);return HttpResponse.json({...input,id:'stored-id',ticketNumber:'SUP-stored-id',status:'Open'});
    }));
    expect((await sendSupportMessage(input)).ticketNumber).toBe('SUP-stored-id');
  });
  it('normalization does not invent weights, IDs or booking timestamps',()=>{
    const value=normalizeParcelRecord({trackingRef:'ZT-SYNTHETIC01',status:'CREATED'});
    expect(value.parcel.weightKg).toBeUndefined(); expect(value.createdAt).toBe('');
    expect(normalizeListParcelsResponse({content:[booking],totalElements:7}).total).toBe(7);
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

 });
