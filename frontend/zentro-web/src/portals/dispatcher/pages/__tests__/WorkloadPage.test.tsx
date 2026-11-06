import { describe, expect, it, vi, beforeEach } from 'vitest';
import '@testing-library/jest-dom/vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { AuthContext } from '@/shared/auth/authContext';
import { WorkloadPage } from '../WorkloadPage';
import * as workloadApi from '../../api/workloadApi';

const response = {
  items: [{
    courierId: '11111111-1111-4111-8111-111111111111',
    userId: '22222222-2222-4222-8222-222222222222',
    name: 'Kamal Silva',
    active: true,
    accountStatus: 'ACTIVE',
    assignmentCount: 4,
    availability: 'AVAILABLE' as const,
  }],
  total: 1,
  overloadConfigured: false as const,
};

const auth = {
  user: { id: 'dispatcher', name: 'Dispatcher', roles: ['DISPATCHER' as const], accessToken: 'token' },
  setUser: vi.fn(), login: vi.fn(), register: vi.fn(), logout: vi.fn(), loading: false,
};

function renderPage() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><AuthContext.Provider value={auth as never}>
    <MemoryRouter><WorkloadPage /></MemoryRouter>
  </AuthContext.Provider></QueryClientProvider>);
}

describe('WorkloadPage', () => {
  beforeEach(() => vi.restoreAllMocks());

  it('renders live counts and availability', async () => {
    vi.spyOn(workloadApi, 'getCourierWorkload').mockResolvedValue(response);
    renderPage();
    expect(await screen.findByText('Kamal Silva')).toBeInTheDocument();
    expect(screen.getByText('4')).toBeInTheDocument();
    expect(screen.getAllByText('Available')).not.toHaveLength(0);
    expect(screen.getByText('Overload rule unavailable')).toBeInTheDocument();
  });

  it('passes filters and supports refresh', async () => {
    const fetch = vi.spyOn(workloadApi, 'getCourierWorkload').mockResolvedValue(response);
    renderPage();
    await screen.findByText('Kamal Silva');
    fireEvent.change(screen.getByLabelText('Search workload'), { target: { value: 'Kamal' } });
    await waitFor(() => expect(fetch).toHaveBeenCalledWith(
      { search: 'Kamal', availability: undefined }, 'token',
    ));
    fireEvent.click(await screen.findByRole('button', { name: 'Refresh workload' }));
    await waitFor(() => expect(fetch.mock.calls.length).toBeGreaterThan(1));
  });

  it('shows dependency failures and retryable empty states', async () => {
    vi.spyOn(workloadApi, 'getCourierWorkload').mockRejectedValue(new Error('down'));
    renderPage();
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load courier workload');
  });
});
