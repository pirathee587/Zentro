import React from 'react';
import { describe, expect, it, vi } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthContext, type Auth } from '@/shared/auth/authContext';
import { OrdersPage } from '../OrdersPage';
import { ApiKeysPage } from '../ApiKeysPage';
import { WebhooksPage } from '../WebhooksPage';
import { BillingPage } from '../BillingPage';

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
  it('renders orders table and filters by status tab', () => {
    renderWithClient(<OrdersPage />);

    expect(screen.getByRole('heading', { name: /^Orders$/i })).toBeInTheDocument();
    expect(screen.getAllByText('LT-5521').length).toBeGreaterThanOrEqual(1);
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

  it('selects order and shows details in right card', () => {
    renderWithClient(<OrdersPage />);

    expect(screen.getByRole('heading', { name: /Order detail/i })).toBeInTheDocument();
    expect(screen.getByText(/Tracking ZT-7K2M9QX4PA/i)).toBeInTheDocument();

    // Select LT-5520
    const row = screen.getByText('LT-5520');
    fireEvent.click(row);

    expect(screen.getByText(/Tracking ZT-3HD8V1NC6R/i)).toBeInTheDocument();
  });
});

describe('ApiKeysPage', () => {
  it('renders API key card and accordion safety guidelines', () => {
    renderWithClient(<ApiKeysPage />);

    expect(screen.getByRole('heading', { name: /^API keys$/i })).toBeInTheDocument();
    expect(screen.getByText('zn_live_8Kd2...')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Generate/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Revoke key/i })).toBeInTheDocument();

    // Accordion toggle
    const storeHeader = screen.getByRole('button', { name: /Where to store it/i });
    expect(storeHeader).toHaveAttribute('aria-expanded', 'true');
    fireEvent.click(storeHeader);
    expect(storeHeader).toHaveAttribute('aria-expanded', 'false');
  });
});

describe('WebhooksPage', () => {
  it('renders endpoint input, checkboxes, and deliveries table', () => {
    renderWithClient(<WebhooksPage />);

    expect(screen.getByRole('heading', { name: /^Webhooks$/i })).toBeInTheDocument();
    expect(screen.getByDisplayValue('https://hooks.lankathreads.lk/swiftdrop')).toBeInTheDocument();
    expect(screen.getByLabelText(/Parcel created/i)).toBeChecked();
    expect(screen.getByLabelText(/Parcel status updated/i)).toBeChecked();

    expect(screen.getAllByText('dlv_6f21').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('Attempts for dlv_6f21')).toBeInTheDocument();
  });
});

describe('BillingPage', () => {
  it('renders statement table, total fees, and size summary breakdown', () => {
    renderWithClient(<BillingPage />);

    expect(screen.getByRole('heading', { name: /^Billing$/i })).toBeInTheDocument();
    expect(screen.getAllByText('LKR 12,148').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('LKR 1,012')).toBeInTheDocument();
    expect(screen.getByText('Small (6)')).toBeInTheDocument();
    expect(screen.getByText('Medium (4)')).toBeInTheDocument();
    expect(screen.getByText('Large (2)')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Print \/ Save as PDF/i })).toBeInTheDocument();
  });
});
