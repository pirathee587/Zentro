import { writeFileSync } from 'node:fs';
import { afterAll, afterEach, describe, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ComponentType } from 'react';
import { AuthContext } from '@/shared/auth/authContext';
import { apiClient } from '@/shared/api/apiClient';
import { auditAccessibility, disabledAxeRules } from '@/shared/testing/accessibility';
import { CustomerLayout } from '@/portals/customer/layout/CustomerLayout';
import { MerchantLayout } from '@/portals/merchant/layout/MerchantLayout';
import { CourierLayout } from '@/portals/courier/layout/CourierLayout';
import { DispatcherLayout } from '@/portals/dispatcher/layout/DispatcherLayout';
import { PublicLayout } from '@/portals/public/layout/PublicLayout';
import { SendParcelPage } from '@/portals/customer/pages/SendParcelPage';
import { HistoryPage } from '@/portals/customer/pages/HistoryPage';
import { SupportPage } from '@/portals/customer/pages/SupportPage';
import { DashboardPage } from '@/portals/merchant/pages/DashboardPage';
import { ApiCredentialsPage } from '@/portals/merchant/pages/ApiCredentialsPage';
import { TodayDeliveriesPage } from '@/portals/courier/pages/TodayDeliveriesPage';
import { DeliveryDetailPage } from '@/portals/courier/pages/DeliveryDetailPage';
import { DispatchQueuePage } from '@/portals/dispatcher/pages/DispatchQueuePage';
import { TrackingSearchPage, TrackingPage } from '@/portals/public/pages/TrackingPages';
import * as customerApi from '@/portals/customer/services/customerApi';

vi.mock('@/shared/domain/tracking/useTrackingStream', () => ({ useTrackingStream: vi.fn() }));
const parcelId = '11111111-1111-4111-8111-111111111111';
const courierId = '22222222-2222-4222-8222-222222222222';
const queueParcel = { parcelId, trackingReference: 'SYNTHETIC-1', pickupSummary: 'Synthetic origin', dropoffSummary: 'Synthetic destination', createdAt: '2026-11-09T00:00:00Z' };
const summary = { parcelId, courierId, assignedAt: '2026-11-09T00:00:00Z', proofId: null, completionState: null };
const cases: { page: string; route: string; layout: ComponentType; component: ComponentType; states: string[] }[] = [
  { page: 'customer-send', route: '/customer/send', layout: CustomerLayout, component: SendParcelPage, states: ['default'] },
  { page: 'customer-history', route: '/customer/history', layout: CustomerLayout, component: HistoryPage, states: ['default', 'loading', 'empty', 'error'] },
  { page: 'customer-support', route: '/customer/support', layout: CustomerLayout, component: SupportPage, states: ['default'] },
  { page: 'merchant-dashboard', route: '/merchant/dashboard', layout: MerchantLayout, component: DashboardPage, states: ['default', 'loading', 'empty', 'error'] },
  { page: 'merchant-credentials', route: '/merchant/dashboard/api-credentials', layout: MerchantLayout, component: ApiCredentialsPage, states: ['default', 'loading', 'empty', 'error', 'secret'] },
  { page: 'courier-list', route: '/courier', layout: CourierLayout, component: TodayDeliveriesPage, states: ['default', 'loading', 'empty', 'error'] },
  { page: 'courier-detail', route: `/courier/deliveries/${parcelId}`, layout: CourierLayout, component: DeliveryDetailPage, states: ['default', 'loading', 'error'] },
  { page: 'dispatcher-queue', route: '/dispatcher/queue', layout: DispatcherLayout, component: DispatchQueuePage, states: ['default', 'loading', 'empty', 'error', 'dialog'] },
  { page: 'public-track-search', route: '/track', layout: PublicLayout, component: TrackingSearchPage, states: ['default'] },
  { page: 'public-track-result', route: '/track/ZTR-ABCDEFG234', layout: PublicLayout, component: TrackingPage, states: ['default', 'loading', 'empty', 'error'] },
];
const reports: unknown[] = [];
afterEach(() => { vi.restoreAllMocks(); localStorage.clear(); document.documentElement.removeAttribute('data-theme'); });
afterAll(() => { if (process.env.A11Y_REPORT_PATH) writeFileSync(process.env.A11Y_REPORT_PATH, '[\n' + reports.map(report => JSON.stringify(report)).join(',\n') + '\n]\n'); });

for (const theme of ['light', 'dark']) describe(`axe DOM checks: ${theme}`, () => {
  for (const entry of cases) for (const state of entry.states) it(`${entry.page} / ${state}`, async () => {
    document.documentElement.setAttribute('data-theme', theme);
    localStorage.setItem('swiftdrop-theme', theme);
    const fail = () => { throw new Error('Synthetic dependency unavailable'); };
    const pending = () => new Promise<never>(() => {});
    const list = state === 'empty' ? [] : [{ id: 'synthetic-parcel', trackingRef: 'SYNTHETIC-1', status: 'Delivered', fee: 100, createdAt: '2026-11-09T00:00:00Z', sender: { city: 'Synthetic origin' }, recipient: { city: 'Synthetic destination' } }];
    vi.spyOn(customerApi, 'listParcels').mockImplementation(state === 'loading' ? pending : state === 'error' ? fail : async () => ({ parcels: list, total: list.length }) as never);
    vi.spyOn(customerApi, 'estimateFee').mockImplementation(pending);
    vi.spyOn(apiClient, 'get').mockImplementation(async url => {
      if (state === 'loading') return pending();
      if (state === 'error') return fail();
      let data: unknown = {};
      if (url.includes('eligible-couriers')) data = [{ id: courierId, userId: parcelId, name: 'Synthetic Courier', active: true, accountStatus: 'ACTIVE' }];
      else if (url.includes('/dispatch/queue')) data = { items: state === 'empty' ? [] : [queueParcel], total: state === 'empty' ? 0 : 1, matchingTotal: state === 'empty' ? 0 : 1, complete: true };
      else if (url.endsWith('/proof')) throw Object.assign(new Error('No evidence'), { isAxiosError: true, response: { status: 404, data: { code: 'PROOF_NOT_FOUND' } } });
      else if (url === '/api/v1/courier/deliveries') data = state === 'empty' ? [] : [summary];
      else if (url.includes('/courier/deliveries/')) data = summary;
      else if (url.includes('/tracking/')) data = { status: 'CREATED', timeline: state === 'empty' ? [] : [{ status: 'CREATED' }] };
      else if (url.includes('api-key')) data = state === 'empty' || state === 'secret' ? null : { active: true, prefix: 'synthetic' };
      else if (url.includes('orders')) data = state === 'empty' ? [] : [{ id: 'synthetic-order', externalOrderRef: 'SYNTHETIC-ORDER', trackingRef: 'SYNTHETIC-1', createdAt: '2026-11-09T00:00:00Z', status: 'DELIVERED', currency: 'LKR', fee: 100 }];
      else data = { businessName: 'Synthetic business', status: 'APPROVED' };
      return { data } as never;
    });
    vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { secret: 'SYNTHETIC-TEST-ONLY', active: true } });
    const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
    const Layout = entry.layout, Page = entry.component;
    const view = render(<QueryClientProvider client={client}><AuthContext.Provider value={{ user: { id: 'synthetic-user', name: 'Synthetic', roles: ['CUSTOMER', 'MERCHANT', 'COURIER', 'DISPATCHER'], accessToken: 'synthetic-token' }, loading: false, logout: vi.fn(), login: vi.fn() } as never}>
      <MemoryRouter initialEntries={[entry.route]}><Routes><Route element={<Layout />}><Route path={entry.page === 'courier-detail' ? '/courier/deliveries/:deliveryId' : entry.page === 'public-track-result' ? '/track/:trackingNumber' : '*'} element={<Page />} /></Route></Routes></MemoryRouter>
    </AuthContext.Provider></QueryClientProvider>);
    if (state !== 'loading') await waitFor(() => expect(client.isFetching()).toBe(0));
    if (entry.page === 'customer-history' && state !== 'loading') await waitFor(() => expect(view.container.querySelector('.sd-table-skeleton-row')).toBeNull());
    if (state === 'dialog') { await userEvent.click(await screen.findByRole('button', { name: 'Assign SYNTHETIC-1' })); await screen.findByRole('option', { name: /Synthetic Courier/ }); }
    if (state === 'secret') { await userEvent.click(await screen.findByRole('button', { name: 'Generate API key' })); await screen.findByRole('button', { name: 'Copy key' }); }
    expect(document.documentElement).toHaveAttribute('data-theme', theme);
    const result = await auditAccessibility(view.container);
    const report = { page: entry.page, state, theme, disabled: disabledAxeRules, violations: result.violations.map(item => ({ id: item.id, impact: item.impact, nodes: item.nodes.length })), incomplete: result.incomplete.map(item => item.id), inapplicable: result.inapplicable.map(item => item.id) };
    reports.push(report);
    console.log(`A11Y ${entry.page}/${state}/${theme}: ${report.violations.length} violations (${report.violations.map(item => item.id).join(',')})`);
    if (!process.env.A11Y_BASELINE) expect(report.violations).toEqual([]);
    view.unmount(); client.clear();
  }, 15000);
});
