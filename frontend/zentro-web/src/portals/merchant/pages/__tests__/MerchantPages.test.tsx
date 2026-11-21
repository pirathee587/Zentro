import React from 'react';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthContext, type Auth } from '@/shared/auth/authContext';
import { OrdersPage } from '../OrdersPage';
import { ApiKeysPage } from '../ApiKeysPage';
import { WebhooksPage } from '../WebhooksPage';
import { BillingPage } from '../BillingPage';
import { http, HttpResponse } from 'msw';
import { server } from '@/shared/testing/mocks/server';

const orderFixtures = [
  { id: 'a1', trackingRef: 'ZT-7K2M9QX4PA', externalOrderRef: 'LT-5521', merchantId: 'merchant-id', status: 'IN_TRANSIT', fee: 1072, currency: 'LKR', estimatedDays: 2, createdAt: '2026-11-03T10:00:00Z', sender: { name: 'Sender', phone: '0770000000', address: 'Colombo', city: 'Colombo' }, recipient: { name: 'Recipient', phone: '0770000001', address: 'Kandy', city: 'Kandy' }, parcel: { weightKg: 2, category: 'General', size: 'MEDIUM', description: null } },
  { id: 'a2', trackingRef: 'ZT-3HD8V1NC6R', externalOrderRef: 'LT-5520', merchantId: 'merchant-id', status: 'DELIVERED', fee: 656, currency: 'LKR', estimatedDays: 1, createdAt: '2026-11-02T10:00:00Z', sender: { name: 'Sender', phone: '0770000000', address: 'Colombo', city: 'Colombo' }, recipient: { name: 'Recipient', phone: '0770000001', address: 'Galle', city: 'Galle' }, parcel: { weightKg: 1, category: 'General', size: 'SMALL', description: null } },
  { id: 'a3', trackingRef: 'ZT-5RN2K8YD1V', externalOrderRef: 'LT-5518', merchantId: 'merchant-id', status: 'FAILED', fee: 1168, currency: 'LKR', estimatedDays: 3, createdAt: '2026-11-01T10:00:00Z', sender: { name: 'Sender', phone: '0770000000', address: 'Colombo', city: 'Colombo' }, recipient: { name: 'Recipient', phone: '0770000001', address: 'Jaffna', city: 'Jaffna' }, parcel: { weightKg: 3, category: 'General', size: 'MEDIUM', description: null } },
];

beforeEach(() => {
  server.use(
    http.get('*/api/v1/merchant/orders', () => HttpResponse.json(orderFixtures)),
    http.get('http://localhost:8080/api/v1/merchants/api-key', () => HttpResponse.json({ id: 'key-1', prefix: 'zn_live_', active: true, createdAt: '2026-10-12T00:00:00Z', rotatedAt: '2026-10-12T00:00:00Z', revokedAt: null })),
    http.get('*/api/v1/merchant/orders/billing', ({ request }) => {
      const month = new URL(request.url).searchParams.get('month') ?? '2026-11';
      return HttpResponse.json({ month, orderCount: 0, quotedFeeTotal: 0, currency: 'LKR', orders: [] });
    }),
    http.get('*/api/v1/merchant/webhooks', () => HttpResponse.json({ id: 'wh-1', url: 'https://hooks.lankathreads.lk/swiftdrop', active: true, secretPrefix: 'whsec_test', createdAt: '2026-11-01T00:00:00Z', updatedAt: '2026-11-01T00:00:00Z' })),
    http.get('*/api/v1/merchant/webhooks/deliveries', () => HttpResponse.json({ items: [{ id: 'dlv_6f21', eventId: 'evt-1', eventType: 'parcel.created', parcelId: 'parcel-1', status: 'DELIVERED', attempts: 1, responseStatus: 200, lastError: null, nextAttemptAt: null, createdAt: '2026-11-04T10:00:00Z', updatedAt: '2026-11-04T10:00:00Z' }], page: 0, size: 20, total: 1 })),
  );
});

const mockAuth: Auth = {
  user: {
    id: 'merchant-id',
    name: 'Lanka Threads',
    roles: ['MERCHANT'],
    accessToken: 'test-token',
  },
  setUser: vi.fn(),
  login: vi.fn(async () => undefined),
  register: vi.fn(async () => undefined),
  logout: vi.fn(async () => undefined),
  loading: false,
};

function renderWithClient(ui: React.ReactElement) {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <AuthContext.Provider value={mockAuth}>
        <MemoryRouter>{ui}</MemoryRouter>
      </AuthContext.Provider>
    </QueryClientProvider>
  );
}

describe('OrdersPage', () => {
  it('renders orders table and filters by status tab', async () => {
    renderWithClient(<OrdersPage />);

    expect(screen.getByRole('heading', { name: /^Orders$/i })).toBeInTheDocument();
    expect(await screen.findAllByText('LT-5521')).toHaveLength(1);
    expect(screen.getByText('LT-5518')).toBeInTheDocument();

    // Filter by Delivered
    const deliveredTab = screen.getByRole('tab', { name: /^Delivered$/i });
    fireEvent.click(deliveredTab);

    expect(screen.getByText('LT-5520')).toBeInTheDocument();
    expect(screen.queryByText('LT-5518')).not.toBeInTheDocument();

    // Filter by Failed
    const failedTab = screen.getByRole('tab', { name: /^Failed$/i });
    fireEvent.click(failedTab);

    expect(screen.getByText('LT-5518')).toBeInTheDocument();
    expect(screen.queryByText('LT-5520')).not.toBeInTheDocument();

    // Filter back to All
    const allTab = screen.getByRole('tab', { name: /^All$/i });
    fireEvent.click(allTab);

    expect(screen.getAllByText('LT-5521').length).toBeGreaterThanOrEqual(1);
  });

  it('selects order and shows details in right card', async () => {
    renderWithClient(<OrdersPage />);

    expect(await screen.findByRole('heading', { name: /Order detail/i })).toBeInTheDocument();
    expect(await screen.findByText(/ZT-7K2M9QX4PA/i)).toBeInTheDocument();

    // Select LT-5520
    const row = screen.getByText('LT-5520');
    fireEvent.click(row);

    expect(screen.getByText(/ZT-3HD8V1NC6R/i)).toBeInTheDocument();
  });
});

describe('ApiKeysPage', () => {
  it('renders API key card and accordion safety guidelines', async () => {
    renderWithClient(<ApiKeysPage />);

    expect(screen.getByRole('heading', { name: /^API keys$/i })).toBeInTheDocument();
    expect(await screen.findByText('zn_live_...')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Generate/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Revoke key/i })).toBeInTheDocument();

    // Accordion toggle
    const storeHeader = screen.getByRole('button', { name: /Where to store it/i });
    expect(storeHeader).toHaveAttribute('aria-expanded', 'true');
    fireEvent.click(storeHeader);
    expect(storeHeader).toHaveAttribute('aria-expanded', 'false');
  });

  it('shows unavailable instead of claiming a key is not configured when metadata fails to load', async () => {
    server.use(http.get('http://localhost:8080/api/v1/merchants/api-key', () => HttpResponse.json({}, { status: 403 })));
    renderWithClient(<ApiKeysPage />);

    expect(await screen.findByText('Unavailable')).toBeInTheDocument();
    expect(screen.queryByText('Not configured')).not.toBeInTheDocument();
    expect(screen.getByRole('alert')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Retry API key' })).toBeInTheDocument();
  });
});

describe('WebhooksPage', () => {
  it('renders endpoint input and delivery records from the merchant APIs', async () => {
    renderWithClient(<WebhooksPage />);

    expect(screen.getByRole('heading', { name: /^Webhooks$/i })).toBeInTheDocument();
    expect(await screen.findByText('https://hooks.lankathreads.lk/swiftdrop')).toBeInTheDocument();
    expect(await screen.findByText('parcel.created')).toBeInTheDocument();
    expect(screen.getByText('DELIVERED')).toBeInTheDocument();
  });
});

describe('BillingPage', () => {
  it('identifies unavailable invoice and payment history without fabricating records', () => {
    renderWithClient(<BillingPage />);

    expect(screen.getByRole('heading', { name: /^Billing$/i })).toBeInTheDocument();
    expect(screen.getByText(/Invoice and payment records are not available/i)).toBeInTheDocument();
  });

  it('offers a retry when monthly billing cannot be loaded', async () => {
    server.use(http.get('*/api/v1/merchant/orders/billing', () => HttpResponse.json({}, { status: 503 })));
    renderWithClient(<BillingPage />);

    expect(await screen.findByRole('alert')).toHaveTextContent('Billing records could not be loaded.');
    expect(screen.getByRole('button', { name: 'Retry' })).toBeInTheDocument();
  });
});
