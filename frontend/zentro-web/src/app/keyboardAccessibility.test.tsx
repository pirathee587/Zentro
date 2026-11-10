import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { AuthContext } from '@/shared/auth/authContext';
import { apiClient } from '@/shared/api/apiClient';
import { Header } from '@/portals/customer/layout/Header';
import { Field } from '@/portals/customer/components/Field';
import { Timeline } from '@/portals/customer/components/Timeline';
import { SendParcelPage } from '@/portals/customer/pages/SendParcelPage';
import { HistoryPage } from '@/portals/customer/pages/HistoryPage';
import { SupportPage } from '@/portals/customer/pages/SupportPage';
import { ProofOfDeliveryForm } from '@/portals/courier/pages/ProofOfDeliveryForm';
import { DispatchQueuePage } from '@/portals/dispatcher/pages/DispatchQueuePage';
import { UsersPage } from '@/portals/dispatcher/pages/UsersPage';
import { ApiCredentialsPage } from '@/portals/merchant/pages/ApiCredentialsPage';
import * as customerApi from '@/portals/customer/services/customerApi';
import { ToastProvider, useToast } from '@/portals/customer/components/Toast';
import { Button } from '@/shared/ui';

function mount(children: ReactNode, path = '/customer') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const view = render(<QueryClientProvider client={client}><AuthContext.Provider value={{ user: { id: 'synthetic-current-user', name: 'Synthetic', accessToken: 'synthetic-token', roles: ['CUSTOMER', 'COURIER', 'DISPATCHER', 'MERCHANT'] }, login: vi.fn(), logout: vi.fn() } as never}><MemoryRouter initialEntries={[path]}>{children}</MemoryRouter></AuthContext.Provider></QueryClientProvider>);
  return { ...view, client };
}
async function tabTo(user: ReturnType<typeof userEvent.setup>, target: HTMLElement) {
  for (let index = 0; index < 60 && document.activeElement !== target; index++) await user.tab();
  expect(target).toHaveFocus();
}
afterEach(() => { vi.restoreAllMocks(); vi.unstubAllGlobals(); localStorage.clear(); document.documentElement.removeAttribute('data-theme'); });

const estimate = { fee: 100, formattedFee: 'LKR 100', currency: 'LKR', estimatedDays: 1, breakdown: { baseFee: 100, weightFee: 0, distanceSurcharge: 0 } };
describe('keyboard and semantic accessibility', () => {
  it('uses a simulated system theme until an explicit theme preference is saved', async () => {
    const user = userEvent.setup();
    const addEventListener = vi.fn<(event: string, listener: (event: MediaQueryListEvent) => void) => void>();
    vi.stubGlobal('matchMedia', () => ({ matches: true, addEventListener, removeEventListener: vi.fn() }));
    mount(<Header />);
    const toggle = screen.getByRole('button', { name: 'Switch to light mode' });
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark');
    await tabTo(user, toggle); await user.keyboard('{Enter}');
    expect(localStorage.getItem('swiftdrop-theme')).toBe('light');
    act(() => addEventListener.mock.calls[0][1]({ matches: true } as MediaQueryListEvent));
    expect(document.documentElement).toHaveAttribute('data-theme', 'light');
  });
  it('announces a toast without moving keyboard focus from its trigger', async () => {
    const user = userEvent.setup();
    function Trigger() {
      const { showToast } = useToast();
      return <Button onClick={() => showToast({ title: 'Synthetic success', durationMs: 0 })}>Show result</Button>;
    }
    mount(<ToastProvider><Trigger /></ToastProvider>);
    const trigger = screen.getByRole('button', { name: 'Show result' });
    await user.tab(); await user.keyboard('{Enter}');
    expect(screen.getByRole('status')).toHaveTextContent('Synthetic success');
    expect(screen.getByRole('status').closest('[aria-live="polite"]')).not.toBeNull();
    expect(trigger).toHaveFocus();
  });
  it('preserves caller descriptions while associating Field errors and required state', () => {
    mount(<><p id="extra">Additional help</p><Field label="Synthetic address" required error="Address is required" aria-describedby="extra" aria-invalid={false} /></>);
    const field = screen.getByLabelText(/Synthetic address/);
    expect(field).toBeRequired(); expect(field).toHaveAttribute('aria-invalid', 'true');
    expect(field).toHaveAccessibleDescription('Additional help Address is required');
    expect(screen.getByRole('alert')).toHaveTextContent('Address is required');
  });
  it('marks the current timeline step without relying on color', () => {
    mount(<Timeline events={[{ id: '1', title: 'Created', status: 'completed', timestamp: 'Synthetic date' }, { id: '2', title: 'In transit', status: 'current', timestamp: 'Synthetic date' }]} />);
    expect(screen.getByText('In transit').closest('[role="listitem"]')).toHaveAttribute('aria-current', 'step');
  });
  it('supports theme and profile disclosure controls using keyboard, including Escape and focus return', async () => {
    const user = userEvent.setup(); mount(<Header />);
    const toggle = screen.getByRole('button', { name: 'Switch to dark mode' });
    await tabTo(user, toggle); await user.keyboard('{Enter}');
    expect(document.documentElement).toHaveAttribute('data-theme', 'dark');
    expect(toggle).toHaveAccessibleName('Switch to light mode');
    const profile = screen.getByRole('button', { name: 'User profile options' });
    await tabTo(user, profile); await user.keyboard(' ');
    expect(profile).toHaveAttribute('aria-expanded', 'true');
    await user.tab(); expect(screen.getByRole('button', { name: 'Sign out' })).toHaveFocus();
    await user.keyboard('{Escape}'); expect(profile).toHaveFocus();
    expect(profile).toHaveAttribute('aria-expanded', 'false');
    expect(screen.queryByRole('button', { name: 'Sign out' })).not.toBeInTheDocument();
    expect(screen.getByRole('link', { name: 'Dashboard' })).toHaveAttribute('aria-current', 'page');
  });
  it('completes booking details, review and confirmation with Tab/Enter and focuses each step', async () => {
    const user = userEvent.setup();
    vi.spyOn(customerApi, 'estimateFee').mockResolvedValue(estimate);
    const create = vi.spyOn(customerApi, 'createDelivery').mockResolvedValue({ trackingRef: 'SYNTHETIC-BOOKING', status: 'CREATED', fee: 100, currency: 'LKR', createdAt: '2026-11-09T00:00:00Z', sender: {}, recipient: {}, parcel: {} } as never);
    mount(<SendParcelPage />);
    for (const [label, value] of [['Pickup Address', 'Synthetic pickup street'], ['Pickup Contact Phone', '0771234567'], ['Drop-off Address', 'Synthetic dropoff street'], ['Recipient Name', 'Synthetic Recipient'], ['Recipient Phone', '0712345678'], ['Weight (kg)', '2']]) {
      const field = screen.getByLabelText(new RegExp(label.replace(/[()]/g, '\\$&'), 'i'));
      await tabTo(user, field); await user.type(field, value, { skipClick: true });
    }
    const review = screen.getByRole('button', { name: /Review/ });
    await waitFor(() => expect(review).toBeEnabled()); await tabTo(user, review); await user.keyboard('{Enter}');
    expect(await screen.findByRole('heading', { name: 'Review Your Delivery' })).toHaveFocus();
    expect(screen.getByText('2 Review')).toHaveAttribute('aria-current', 'step');
    const confirm = screen.getByRole('button', { name: /Confirm/ });
    await tabTo(user, confirm); await user.keyboard('{Enter}');
    expect(await screen.findByRole('heading', { name: 'Delivery Confirmed!' })).toHaveFocus();
    expect(create).toHaveBeenCalledOnce();
  });
  it('focuses the first invalid booking field and associates its error', async () => {
    vi.spyOn(customerApi, 'estimateFee').mockResolvedValue(estimate);
    const { container } = mount(<SendParcelPage />);
    fireEvent.submit(container.querySelector('form')!);
    await waitFor(() => expect(screen.getByLabelText(/Pickup Address/)).toHaveFocus());
    expect(screen.getByLabelText(/Pickup Address/)).toHaveAttribute('aria-invalid', 'true');
    expect(screen.getByLabelText(/Pickup Address/)).toHaveAccessibleDescription();
  });
  it('operates History filter chips by keyboard and retains Route/Date in the scrollable table', async () => {
    const user = userEvent.setup();
    const list = vi.spyOn(customerApi, 'listParcels').mockResolvedValue({ parcels: [{ id: 'synthetic', trackingRef: 'SYNTHETIC-1', status: 'Delivered', fee: 100, createdAt: '2026-11-09', sender: { city: 'Synthetic origin' }, recipient: { city: 'Synthetic destination' } }], total: 1 } as never);
    mount(<HistoryPage />);
    const delivered = screen.getByRole('button', { name: 'Delivered' });
    await tabTo(user, delivered); await user.keyboard(' ');
    expect(delivered).toHaveAttribute('aria-pressed', 'true');
    await waitFor(() => expect(list).toHaveBeenLastCalledWith(expect.objectContaining({ status: 'Delivered' })));
    const table = await screen.findByRole('table', { name: 'Deliveries table' });
    expect(table.parentElement).toHaveClass('sd-table-wrap');
    expect(within(table).getByRole('columnheader', { name: 'Route' })).toHaveAttribute('scope', 'col');
    expect(within(table).getByRole('columnheader', { name: 'Date' })).toBeInTheDocument();
    expect(table).toHaveTextContent('Synthetic origin');
  });
  it('sends Support chat with Enter and retains native FAQ disclosure semantics', async () => {
    const user = userEvent.setup(); const ask = vi.spyOn(customerApi, 'askAssistant').mockResolvedValue({ answer: 'Synthetic answer' } as never);
    mount(<SupportPage />);
    const input = screen.getByRole('textbox', { name: 'Message to delivery assistant' });
    await tabTo(user, input); await user.type(input, 'Synthetic delivery question', { skipClick: true }); await user.keyboard('{Enter}');
    await waitFor(() => expect(ask).toHaveBeenCalledWith('Synthetic delivery question'));
    const summary = document.querySelector('summary')!;
    // user-event 14 does not include summary in its tab selector. Browser keyboard behavior is manual.
    expect(summary.parentElement?.tagName).toBe('DETAILS');
    await user.click(summary);
    expect(summary.closest('details')).toHaveAttribute('open');
  });
  it('focuses invalid proof inputs and submits valid proof via keyboard', async () => {
    const user = userEvent.setup(); const saved = vi.fn(); const save = vi.fn().mockResolvedValue({ proofId: 'synthetic' });
    mount(<ProofOfDeliveryForm save={save} saved={saved} reconcile={vi.fn().mockResolvedValue(null)} />);
    await tabTo(user, screen.getByRole('button', { name: 'Submit proof' })); await user.keyboard('{Enter}');
    expect(screen.getByLabelText(/Recipient Name/)).toHaveFocus();
    await user.type(screen.getByLabelText(/Recipient Name/), 'Synthetic Recipient', { skipClick: true });
    const reference = screen.getByLabelText(/Proof Reference/); await tabTo(user, reference); await user.type(reference, 'SYNTHETIC-REF', { skipClick: true });
    await tabTo(user, screen.getByRole('button', { name: 'Submit proof' })); await user.keyboard('{Enter}');
    await waitFor(() => expect(save).toHaveBeenCalledOnce());
  });
  it('traps assignment dialog focus, supports Escape, returns focus and confirms once', async () => {
    const user = userEvent.setup(); const parcelId = '11111111-1111-4111-8111-111111111111', courierId = '22222222-2222-4222-8222-222222222222';
    vi.spyOn(apiClient, 'get').mockImplementation(async url => ({ data: url.includes('eligible-couriers') ? [{ id: courierId, userId: parcelId, name: 'Synthetic Courier', active: true, accountStatus: 'ACTIVE' }] : { items: [{ parcelId, trackingReference: 'SYNTHETIC-1', pickupSummary: null, dropoffSummary: null, createdAt: '2026-11-09T00:00:00Z' }], total: 1, matchingTotal: 1, complete: true } }) as never);
    const post = vi.spyOn(apiClient, 'post').mockResolvedValue({});
    mount(<DispatchQueuePage />);
    const trigger = await screen.findByRole('button', { name: 'Assign SYNTHETIC-1' });
    await tabTo(user, trigger); await user.keyboard('{Enter}');
    const dialog = await screen.findByRole('dialog'); expect(dialog).toHaveFocus();
    await screen.findByRole('option', { name: /Synthetic Courier/ });
    await user.tab({ shift: true }); expect(within(dialog).getByRole('button', { name: 'Cancel' })).toHaveFocus();
    await user.keyboard('{Escape}'); expect(trigger).toHaveFocus();
    await user.keyboard('{Enter}');
    const select = await screen.findByLabelText('Courier');
    await user.selectOptions(select, courierId); // Native selection simulated; browser arrow behavior remains manual.
    await tabTo(user, screen.getByRole('button', { name: 'Confirm assignment' })); await user.keyboard('{Enter}');
    await waitFor(() => expect(post).toHaveBeenCalledOnce());
  });
  it('traps the user edit panel and restores the trigger after Escape', async () => {
    const user = userEvent.setup();
    vi.spyOn(apiClient, 'get').mockResolvedValue({ data: [{ id: 'synthetic-user', name: 'Synthetic User', email: 'synthetic@example.invalid', accountStatus: 'ACTIVE', enabled: true, roles: ['CUSTOMER'], merchantStatus: null }] });
    mount(<UsersPage />);
    const trigger = await screen.findByRole('button', { name: 'Edit user Synthetic User' });
    await tabTo(user, trigger); await user.keyboard('{Enter}');
    const dialog = screen.getByRole('dialog', { name: 'Edit user' }); expect(dialog).toHaveFocus();
    await user.tab({ shift: true }); expect(dialog.contains(document.activeElement)).toBe(true);
    await user.keyboard('{Escape}'); expect(trigger).toHaveFocus();
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
  });
  it('focuses the one-time secret heading and copies without placing the secret in live regions', async () => {
    const user = userEvent.setup();
    vi.spyOn(apiClient, 'get').mockResolvedValue({ data: null });
    vi.spyOn(apiClient, 'post').mockResolvedValue({ data: { secret: 'SYNTHETIC-TEST-KEY' } });
    const clipboard = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue();
    mount(<ApiCredentialsPage />);
    const generate = await screen.findByRole('button', { name: 'Generate API key' });
    await tabTo(user, generate); await user.keyboard('{Enter}');
    const heading = await screen.findByText('Copy and store this key now'); expect(heading).toHaveFocus();
    expect(screen.getByText('SYNTHETIC-TEST-KEY').closest('[role="status"], [aria-live]')).toBeNull();
    await user.tab(); expect(screen.getByRole('button', { name: 'Copy key' })).toHaveFocus(); await user.keyboard('{Enter}');
    await waitFor(() => expect(clipboard).toHaveBeenCalledWith('SYNTHETIC-TEST-KEY'));
    expect(await screen.findByRole('status')).toHaveTextContent('Key copied.');
  });
});
