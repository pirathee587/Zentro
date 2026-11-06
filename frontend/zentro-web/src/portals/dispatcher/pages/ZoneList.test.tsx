import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/shared/testing/mocks/server';
import { ZoneList } from './ZoneList';

const ZONES_URL = 'http://localhost:8080/api/v1/zones';

function renderList() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(<QueryClientProvider client={client}><ZoneList /></QueryClientProvider>);
}

describe('ZoneList', () => {
  it('shows loading, then each zone from the API with its status', async () => {
    server.use(http.get(ZONES_URL, () => HttpResponse.json([
      { id: 'z1', code: 'ZONE_COLOMBO_METRO', name: 'Colombo Metro', description: 'Colombo city', active: true },
      { id: 'z2', code: 'ZONE_NORTHERN', name: 'Northern', description: null, active: false },
    ])));
    renderList();

    expect(screen.getByRole('status')).toHaveTextContent('Loading delivery zones');
    const table = await screen.findByRole('table', { name: 'Delivery zones' });
    const rows = within(table).getAllByRole('row');
    expect(rows).toHaveLength(3);
    expect(rows[1]).toHaveTextContent('ZONE_COLOMBO_METRO');
    expect(rows[1]).toHaveTextContent('Colombo city');
    expect(rows[1]).toHaveTextContent('Active');
    expect(rows[2]).toHaveTextContent('Northern');
    expect(rows[2]).toHaveTextContent('—');
    expect(rows[2]).toHaveTextContent('Inactive');
    expect(screen.getByText(/Zones are read-only here/)).toBeInTheDocument();
    expect(screen.queryByRole('button')).not.toBeInTheDocument();
  });

  it('shows an empty state when no zones are configured', async () => {
    server.use(http.get(ZONES_URL, () => HttpResponse.json([])));
    renderList();

    expect(await screen.findByText('No delivery zones are configured.')).toBeInTheDocument();
    expect(screen.queryByRole('table')).not.toBeInTheDocument();
  });

  it('shows an error with a retry that loads the zones', async () => {
    let calls = 0;
    server.use(http.get(ZONES_URL, () => {
      calls += 1;
      return calls === 1
        ? new HttpResponse(null, { status: 500 })
        : HttpResponse.json([{ id: 'z1', code: 'ZONE_CENTRAL', name: 'Central', description: null, active: true }]);
    }));
    renderList();

    expect(await screen.findByRole('alert')).toHaveTextContent('Pricing is temporarily unavailable. Please try again.');
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));

    expect(await screen.findByText('ZONE_CENTRAL')).toBeInTheDocument();
  });
});
