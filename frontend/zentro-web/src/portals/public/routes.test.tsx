import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { MemoryRouter, useRoutes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { portalRoutes } from './routes';
import { server } from '@/shared/testing/mocks/server';

function PublicPortal() {
  return useRoutes(portalRoutes);
}

const renderAt = (path: string) => render(
  <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
    <MemoryRouter initialEntries={[path]}><PublicPortal /></MemoryRouter>
  </QueryClientProvider>,
);

describe('public portal routes', () => {
  it.each(['/no-such-page', '/merchantx', '/customer-dashboard', '/track/a/b/c'])(
    'shows page not found with a way home for the unknown path %s',
    (path) => {
      renderAt(path);
      expect(screen.getByRole('heading', { level: 1, name: 'Page not found' })).toBeInTheDocument();
      expect(screen.getByRole('link', { name: /Zentro home page/i })).toHaveAttribute('href', '/');
    },
  );

  it('keeps known public pages working', () => {
    renderAt('/track');
    expect(screen.queryByRole('heading', { name: 'Page not found' })).not.toBeInTheDocument();
  });

  it('uses the full SwiftDrop TrackView for direct courier tracking URLs', () => {
    server.use(http.post('*/graphql', () => HttpResponse.json({ data: { publicTracking: {
      trackingRef: 'ZT-F986B4GR5N', status: 'Created', rawStatus: 'CREATED', category: 'Parcel', size: 'SMALL',
      description: null, originCity: null, destinationCity: null, estimatedDays: null, estimatedDeliveryDate: null,
      createdAt: '2026-11-21T00:00:00Z', deliveredAt: null, failureReason: null, events: [],
    } } })));
    renderAt('/track/ZT-F986B4GR5N');
    expect(screen.getByRole('heading', { name: /Track a parcel/i })).toBeInTheDocument();
    expect(screen.queryByRole('heading', { name: /Your parcel.s journey/i })).not.toBeInTheDocument();
  });
});
