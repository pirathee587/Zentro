import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { MemoryRouter } from 'react-router-dom';
import { StrictMode } from 'react';
import { Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { AuthContext, type Auth } from '@/shared/auth/authContext';
import { principalCacheScope, queryKeys } from '@/shared/api/queryKeys';
import { server } from '@/shared/testing/mocks/server';
import { TodayDeliveriesPage } from '../TodayDeliveriesPage';
import { CourierLayout } from '../../layout/CourierLayout';
import { invalidateAfterCourierProof } from '@/shared/api/queryInvalidation';
import { dashboardDelivery, dashboardEnvelope, dashboardFixture } from './dashboardFixtures';

const user: NonNullable<Auth['user']> = { id: 'courier-a', name: 'Synthetic Courier', roles: ['COURIER'], accessToken: 'synthetic-a' };
function setup(strict = false) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const login = vi.fn();
  const tree = (account: Auth['user']) => <QueryClientProvider client={client}><AuthContext.Provider value={{
    user: account, login, logout: vi.fn(), register: vi.fn(), setUser: vi.fn(), loading: false,
  } as never}><MemoryRouter>{strict ? <StrictMode><Routes><Route element={<CourierLayout />}>
    <Route path="*" element={<TodayDeliveriesPage />} /></Route></Routes></StrictMode> : <TodayDeliveriesPage />}</MemoryRouter></AuthContext.Provider></QueryClientProvider>;
  const view = render(tree(user));
  return { ...view, client, login, account: (account: Auth['user']) => view.rerender(tree(account)) };
}
describe('Courier Today dashboard', () => {
  it.each([['  Fixture Courier  ', 'Welcome back, Fixture Courier'], [undefined, 'Welcome back'], ['   ', 'Welcome back']])(
    'uses the authenticated name or a neutral greeting for %s', (name, expected) => {
      server.use(http.post('*/graphql', () => HttpResponse.json(dashboardEnvelope([]))));
      const session = setup();
      session.account({ ...user, name });
      expect(screen.getByRole('heading', { level: 1, name: expected })).toBeInTheDocument();
    },
  );
  it('keeps the active dashboard in the query cache and refetches after REST success in StrictMode', async () => {
    server.use(http.post('*/graphql', () => HttpResponse.json(dashboardEnvelope([dashboardDelivery({ trackingRef: 'ZT-BEFORE' })]))));
    const session = setup(true); await screen.findByText('ZT-BEFORE');
    expect(session.client.getQueryData(queryKeys.courier.dashboard(principalCacheScope(user.id), { page: 1, size: 10 }))).toBeDefined();
    server.use(http.post('*/graphql', () => HttpResponse.json(dashboardEnvelope([dashboardDelivery({ trackingRef: 'ZT-AFTER' })]))));
    await act(async () => { await invalidateAfterCourierProof(session.client, 'parcel', principalCacheScope(user.id)); });
    expect(await screen.findByText('ZT-AFTER')).toBeInTheDocument();
  });
  it('uses backend totals and current delivery instead of calculating either from the page', async () => {
    const next = dashboardDelivery({ parcelId: '22222222-2222-4222-8222-222222222222', trackingRef: 'ZT-NEXT', status: 'OUT_FOR_DELIVERY',
      recipient: { name: 'Synthetic Recipient', phone: 'synthetic', address: 'Synthetic destination', city: 'Colombo' }, completionState: 'PENDING' });
    const row = dashboardDelivery({ trackingRef: 'ZT-PAGE', status: 'IN_TRANSIT' });
    server.use(http.post('*/graphql', () => HttpResponse.json({ data: { courierTodayDashboard: dashboardFixture([row], {
      counts: { assigned: 12, delivered: 4, failed: 1, remaining: 7 }, currentDelivery: next,
      pageInfo: { page: 1, size: 10, totalElements: 12, totalPages: 2, hasNext: true, hasPrevious: false },
    }) } })));
    setup(); await screen.findByText('ZT-NEXT');
    const stats = screen.getByRole('region', { name: 'Delivery statistics' });
    expect(within(stats).getByText('Assigned').parentElement).toHaveTextContent('12');
    expect(within(stats).getByText('Delivered').parentElement).toHaveTextContent('4');
    expect(within(stats).getByText('Remaining').parentElement).toHaveTextContent('7');
    expect(screen.getByRole('link', { name: 'Open delivery' })).toHaveAttribute('href', `/courier/deliveries/${next.parcelId}`);
    expect(screen.getByRole('link', { name: 'View delivery' })).toHaveAttribute('href', `/courier/deliveries/${row.parcelId}`);
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuetext', 'Out for delivery');
    expect(screen.getByText(/completion pending confirmation/)).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /Mark as|Mark failed/ })).not.toBeInTheDocument();
    expect(screen.getByText('Monday, 16 November 2026')).toBeInTheDocument();
  });
  it('shows loading then a visible empty state without fake counters or deliveries', async () => {
    let release!: () => void; const gate = new Promise<void>(resolve => { release = resolve; });
    server.use(http.post('*/graphql', async () => { await gate; return HttpResponse.json(dashboardEnvelope([])); }));
    setup(); expect(screen.getByRole('status')).toHaveTextContent('Loading deliveries');
    expect(screen.queryByText('Assigned')).not.toBeInTheDocument();
    await act(async () => { release(); });
    expect(await screen.findByText('No deliveries are assigned to you yet.')).toBeVisible();
    expect(screen.queryByText('GPS on')).not.toBeInTheDocument();
  });
  it('shows missing projection and safe partial-data feedback', async () => {
    const row = dashboardDelivery({ projectionAvailable: false, trackingRef: null, status: null });
    server.use(http.post('*/graphql', () => HttpResponse.json({ ...dashboardEnvelope([row]), errors: [{
      message: 'PRIVATE-EXCEPTION', path: ['courierTodayDashboard', 'deliveries', 0, 'recipient'],
    }] })));
    setup(); expect(await screen.findByText('Shipment details are not available yet.')).toBeInTheDocument();
    expect(screen.getByText('Status unavailable')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Some delivery details could not be loaded');
    expect(screen.queryByText('PRIVATE-EXCEPTION')).not.toBeInTheDocument();
    expect(screen.queryByRole('progressbar')).not.toBeInTheDocument();
  });
  it('paginates with backend pageInfo while retaining population totals', async () => {
    const pages: number[] = [];
    server.use(http.post('*/graphql', async ({ request }) => {
      const { variables } = await request.json() as { variables: { page: number } }; pages.push(variables.page);
      return HttpResponse.json({ data: { courierTodayDashboard: dashboardFixture([dashboardDelivery({ trackingRef: `ZT-PAGE-${variables.page}` })], {
        counts: { assigned: 11, delivered: 0, failed: 0, remaining: 11 },
        pageInfo: { page: variables.page, size: 10, totalElements: 11, totalPages: 2, hasNext: variables.page === 1, hasPrevious: variables.page === 2 },
      }) } });
    }));
    setup(); await screen.findByText('ZT-PAGE-1'); expect(screen.getByRole('button', { name: 'Previous' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    await screen.findByText('ZT-PAGE-2'); expect(screen.getByText('Page 2 of 2')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Next' })).toBeDisabled();
    expect(screen.getByText('Assigned').parentElement).toHaveTextContent('11'); expect(pages).toEqual([1, 2]);
  });
  it('clears private dashboard data on an authorization failure, without retrying it', async () => {
    server.use(http.post('*/graphql', () => HttpResponse.json(dashboardEnvelope([dashboardDelivery({ trackingRef: 'ZT-PRIVATE' })]))));
    const { client } = setup(); await screen.findByText('ZT-PRIVATE'); let calls = 0;
    server.use(http.post('*/graphql', () => { calls++; return HttpResponse.json({ ...dashboardEnvelope(), errors: [{
      extensions: { classification: 'FORBIDDEN' }, message: 'PRIVATE-ERROR', path: ['courierTodayDashboard'],
    }] }); }));
    fireEvent.click(screen.getByRole('button', { name: 'Refresh' }));
    expect(await screen.findByRole('alert')).toHaveTextContent("You don't have access");
    expect(screen.queryByText('ZT-PRIVATE')).not.toBeInTheDocument(); expect(calls).toBe(1);
    expect(screen.queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument();
    expect(JSON.stringify(client.getQueriesData({ queryKey: queryKeys.courier.dashboard() }))).not.toContain('ZT-PRIVATE');
  });
  it('isolates account caches, resets pagination and cancels delayed responses on switching or logout', async () => {
    let release!: () => void, started!: () => void;
    const gate = new Promise<void>(resolve => { release = resolve; }); const requested = new Promise<void>(resolve => { started = resolve; });
    server.use(http.post('*/graphql', async ({ request }) => {
      if (request.headers.get('Authorization') === 'Bearer synthetic-a') { started(); await gate;
        return HttpResponse.json(dashboardEnvelope([dashboardDelivery({ trackingRef: 'ZT-OLD-ACCOUNT' })])); }
      return HttpResponse.json(dashboardEnvelope([dashboardDelivery({ trackingRef: 'ZT-NEW-ACCOUNT' })]));
    }));
    const session = setup(); await requested;
    session.account({ ...user, id: 'courier-b', accessToken: 'synthetic-b' });
    await screen.findByText('ZT-NEW-ACCOUNT'); await act(async () => { release(); });
    expect(screen.queryByText('ZT-OLD-ACCOUNT')).not.toBeInTheDocument();
    expect(session.client.getQueriesData({ queryKey: queryKeys.courier.dashboard(principalCacheScope(user.id)) })).toEqual([]);
    session.account(null); expect(await screen.findByText('Sign in to view your deliveries.')).toBeInTheDocument();
    await waitFor(() => expect(session.client.getQueriesData({ queryKey: queryKeys.courier.dashboard() })).toEqual([]));
    expect(screen.queryByText('ZT-NEW-ACCOUNT')).not.toBeInTheDocument();
  });
});
