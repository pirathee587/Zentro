import { beforeEach, describe, expect, it, vi } from 'vitest';
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse, delay } from 'msw';
import { AuthContext } from '@/shared/auth/authContext';
import type { Auth } from '@/shared/auth/authContext';
import { ProtectedRoute } from '@/shared/auth/ProtectedRoute';
import { server } from '@/shared/testing/mocks/server';
import { TodayDeliveriesPage } from '../TodayDeliveriesPage';
import { DeliveryDetailPage } from '../DeliveryDetailPage';
import { ProofOfDeliveryForm } from '../ProofOfDeliveryForm';
import type { ProofResponse } from '../../api/deliveryApi';

const parcelId = '11111111-1111-4111-8111-111111111111';
const root = `*/api/v1/courier/deliveries`;
const summary = { parcelId, courierId: 'canonical-profile', assignedAt: '2026-11-04T10:00:00Z', proofId: null, completionState: null };
const savedProof: ProofResponse = { proofId: 'proof-1', parcelId, recipientName: 'Nimal', notes: null,
  proofReference: 'REF-1', submittedAt: '2026-11-04T11:00:00Z', completionState: 'PENDING', completedAt: null };
const auth = { user: { id: 'app-user', name: 'Courier', roles: ['COURIER'], accessToken: 'courier-test-token' },
  login: vi.fn(), logout: vi.fn(), register: vi.fn(), setUser: vi.fn(), loading: false };
let currentProof: ProofResponse | null;
let requests: number;
let submitted: unknown;

function renderPages(path = `/courier/deliveries/${parcelId}`) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(<QueryClientProvider client={client}><AuthContext.Provider value={auth as never}>
    <MemoryRouter initialEntries={[path]}><Routes>
      <Route path="/courier" element={<TodayDeliveriesPage />} />
      <Route path="/courier/deliveries/:deliveryId" element={<DeliveryDetailPage />} />
    </Routes></MemoryRouter>
  </AuthContext.Provider></QueryClientProvider>);
}
function fillForm() {
  fireEvent.change(screen.getByLabelText(/Recipient Name/), { target: { value: ' Nimal ' } });
  fireEvent.change(screen.getByLabelText(/Proof Reference/), { target: { value: ' REF-1 ' } });
}

// Keep the same router and query cache while changing the authenticated account.
function renderSession(path = '/courier') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  const initial = auth as unknown as Auth;
  function tree(session: Auth) {
    return <QueryClientProvider client={client}><AuthContext.Provider value={session}>
      <MemoryRouter initialEntries={[path]}><Routes><Route element={<ProtectedRoute />}>
        <Route path="/courier" element={<TodayDeliveriesPage />} />
        <Route path="/courier/deliveries/:deliveryId" element={<DeliveryDetailPage />} />
      </Route></Routes></MemoryRouter>
    </AuthContext.Provider></QueryClientProvider>;
  }
  const view = render(tree(initial));
  return { change: (user: Auth['user']) => view.rerender(tree({ ...initial, user })) };
}
beforeEach(() => {
  currentProof = null; requests = 0; submitted = undefined;
  server.use(
    http.get(root, () => HttpResponse.json([summary])),
    http.get(`${root}/${parcelId}`, () => HttpResponse.json(summary)),
    http.get(`${root}/${parcelId}/proof`, () => currentProof ? HttpResponse.json(currentProof)
      : HttpResponse.json({ code: 'PROOF_NOT_FOUND' }, { status: 404 })),
    http.post(`${root}/${parcelId}/proof`, async ({ request }) => {
      requests++; submitted = await request.json(); currentProof = savedProof;
      return HttpResponse.json(savedProof, { status: 201 });
    }),
  );
});
describe('courier delivery pages', () => {
  it('does not show courier A delayed list response after switching to courier B', async () => {
    let release!: () => void;
    let started!: () => void;
    let finished!: () => void;
    const gate = new Promise<void>(resolve => { release = resolve; });
    const requested = new Promise<void>(resolve => { started = resolve; });
    const responded = new Promise<void>(resolve => { finished = resolve; });
    const otherId = '22222222-2222-4222-8222-222222222222';
    server.use(http.get(root, async ({ request }) => {
      if (request.headers.get('Authorization') === 'Bearer courier-test-token') {
        started(); await gate; finished(); return HttpResponse.json([summary]);
      }
      return HttpResponse.json([{ ...summary, parcelId: otherId }]);
    }));
    const session = renderSession();
    await requested;
    session.change({ ...auth.user, id: 'courier-b', accessToken: 'token-b' } as Auth['user']);
    expect(await screen.findByText(otherId)).toBeInTheDocument();
    await act(async () => { release(); await responded; });
    expect(screen.queryByText(parcelId)).not.toBeInTheDocument();
  });
  it('unmounts saved evidence and requests sign-in on logout', async () => {
    currentProof = { ...savedProof, completionState: 'COMPLETED' };
    const session = renderSession(`/courier/deliveries/${parcelId}`);
    await screen.findByText('Nimal');
    session.change(null);
    expect(await screen.findByText('Redirecting to sign in...')).toBeInTheDocument();
    expect(screen.queryByText('Nimal')).not.toBeInTheDocument();
    expect(auth.login).toHaveBeenCalledWith(`/courier/deliveries/${parcelId}`, undefined, 'staff');
  });
  it('clears unsaved evidence when the account changes on the same delivery route', async () => {
    const session = renderSession(`/courier/deliveries/${parcelId}`);
    await screen.findByLabelText(/Recipient Name/); fillForm();
    session.change({ ...auth.user, id: 'courier-b', accessToken: 'token-b' } as Auth['user']);
    // The server will normally deny B. Even while it is pending, A's draft must disappear.
    await waitFor(() => expect(screen.queryByDisplayValue(' Nimal ')).not.toBeInTheDocument());
  });
  it.each(['COMPLETED', 'REJECTED', 'BLOCKED'] as const)('polls PENDING proof to %s', async state => {
    currentProof = savedProof;
    renderPages();
    await screen.findByText('Proof recorded. Delivery completion is pending confirmation.');
    currentProof = { ...savedProof, completionState: state, completedAt: state === 'COMPLETED' ? '2026-11-04T11:01:00Z' : null };
    await waitFor(() => expect(screen.getByRole('status')).toHaveTextContent(
      state === 'COMPLETED' ? 'Delivery completed' : 'Contact dispatch'), { timeout: 4500 });
    expect(screen.getByText('Nimal')).toBeInTheDocument();
  });
  it('hides previously displayed proof when a refresh is denied', async () => {
    currentProof = savedProof;
    renderPages(); await screen.findByText('Nimal');
    server.use(http.get(`${root}/${parcelId}/proof`, () => HttpResponse.json({}, { status: 403 })));
    fireEvent.click(screen.getByRole('button', { name: 'Refresh completion state' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('permission');
    expect(screen.queryByText('Nimal')).not.toBeInTheDocument();
  });
  it('lists authorized deliveries and navigates to details', async () => {
    renderPages('/courier');
    fireEvent.click(await screen.findByRole('link', { name: 'View delivery' }));
    expect(await screen.findByRole('heading', { name: 'Delivery details' })).toBeInTheDocument();
    expect(await screen.findByLabelText(/Recipient Name/)).toBeInTheDocument();
  });
  it('shows a meaningful empty assignment state', async () => {
    server.use(http.get(root, () => HttpResponse.json([])));
    renderPages('/courier');
    expect(await screen.findByText('No deliveries are assigned to you yet.')).toBeInTheDocument();
  });
  it('shows list loading and dependency errors', async () => {
    server.use(http.get(root, async () => { await delay(60); return HttpResponse.json({}, { status: 503 }); }));
    renderPages('/courier');
    expect(screen.getByRole('status')).toHaveTextContent('Loading deliveries');
    expect(await screen.findByRole('alert')).toHaveTextContent('unavailable');
  });
  it('validates required fields before any POST and focuses the first field', async () => {
    renderPages();
    fireEvent.click(await screen.findByRole('button', { name: 'Submit proof' }));
    expect(screen.getAllByText('This field is required.')).toHaveLength(2);
    expect(screen.getByLabelText(/Recipient Name/)).toHaveFocus(); expect(requests).toBe(0);
  });
  it('normalizes fields, permits optional notes and shows pending completion', async () => {
    renderPages(); await screen.findByLabelText(/Recipient Name/); fillForm();
    fireEvent.click(screen.getByRole('button', { name: 'Submit proof' }));
    expect(await screen.findByRole('heading', { name: 'Saved proof' })).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('pending confirmation');
    expect(submitted).toEqual({ recipientName: 'Nimal', notes: null, proofReference: 'REF-1' });
    expect(screen.queryByRole('button', { name: 'Submit proof' })).not.toBeInTheDocument();
  });
  it('prevents duplicate clicks while the request is active', async () => {
    server.use(http.post(`${root}/${parcelId}/proof`, async () => {
      requests++; await delay(100); currentProof = savedProof; return HttpResponse.json(savedProof);
    }));
    renderPages(); await screen.findByLabelText(/Recipient Name/); fillForm();
    const button = screen.getByRole('button', { name: 'Submit proof' });
    fireEvent.click(button); fireEvent.click(button);
    expect(button).toBeDisabled();
    await screen.findByRole('heading', { name: 'Saved proof' }); expect(requests).toBe(1);
  });
  it('retrieves immutable proof and reports confirmed completion', async () => {
    currentProof = { ...savedProof, completionState: 'COMPLETED', completedAt: '2026-11-04T11:01:00Z' };
    renderPages();
    expect(await screen.findByText('Nimal')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Delivery completed');
    expect(screen.queryByLabelText(/Recipient Name/)).not.toBeInTheDocument();
  });
  it.each(['REJECTED', 'BLOCKED'] as const)('retains saved evidence and explains %s completion', async state => {
    currentProof = { ...savedProof, completionState: state }; renderPages();
    expect(await screen.findByText('Nimal')).toBeInTheDocument();
    expect(screen.getByRole('status')).toHaveTextContent('Contact dispatch');
  });
  it.each([401, 403] as const)('does not show evidence after access failure %s', async status => {
    server.use(http.get(`${root}/${parcelId}`, () => HttpResponse.json({}, { status })));
    renderPages();
    expect(await screen.findByRole('alert')).toHaveTextContent(status === 401 ? 'session has expired' : 'permission');
    expect(screen.queryByLabelText(/Recipient Name/)).not.toBeInTheDocument();
    expect(screen.queryByText('Nimal')).not.toBeInTheDocument();
  });
  it('preserves entries and displays backend validation errors', async () => {
    server.use(http.post(`${root}/${parcelId}/proof`, () => HttpResponse.json({ fieldErrors: { proofReference: 'Reference is invalid.' } }, { status: 400 })));
    renderPages(); await screen.findByLabelText(/Recipient Name/); fillForm();
    fireEvent.click(screen.getByRole('button', { name: 'Submit proof' }));
    expect(await screen.findByText('Reference is invalid.')).toBeInTheDocument();
    expect(screen.getByLabelText(/Recipient Name/)).toHaveValue(' Nimal ');
  });
  it('reconciles a lost POST response and never resubmits saved evidence', async () => {
    server.use(http.post(`${root}/${parcelId}/proof`, () => { requests++; currentProof = savedProof; return HttpResponse.error(); }));
    renderPages(); await screen.findByLabelText(/Recipient Name/); fillForm();
    fireEvent.click(screen.getByRole('button', { name: 'Submit proof' }));
    expect(await screen.findByRole('heading', { name: 'Saved proof' })).toBeInTheDocument(); expect(requests).toBe(1);
  });
  it('shows conflict guidance without overwriting entries', async () => {
    server.use(http.post(`${root}/${parcelId}/proof`, () => HttpResponse.json({ code: 'PROOF_CONFLICT' }, { status: 409 })));
    renderPages(); await screen.findByLabelText(/Recipient Name/); fillForm();
    fireEvent.click(screen.getByRole('button', { name: 'Submit proof' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('different evidence');
    expect(screen.getByLabelText(/Proof Reference/)).toHaveValue(' REF-1 ');
  });
});
describe('proof form limits', () => {
  it('rejects a null character before sending evidence', () => {
    const save = vi.fn();
    render(<ProofOfDeliveryForm save={save} reconcile={vi.fn()} saved={vi.fn()} />);
    fillForm();
    fireEvent.change(screen.getByLabelText(/Delivery Notes/), { target: { value: 'note\0' } });
    fireEvent.click(screen.getByRole('button', { name: 'Submit proof' }));
    expect(screen.getByText('The null character is not allowed.')).toBeInTheDocument();
    expect(save).not.toHaveBeenCalled();
  });
  it.each([['Recipient Name', 101], ['Delivery Notes', 501], ['Proof Reference', 201]] as const)('rejects oversize %s', (label, size) => {
    const save = vi.fn();
    render(<ProofOfDeliveryForm save={save} reconcile={vi.fn()} saved={vi.fn()} />);
    fillForm(); fireEvent.change(screen.getByLabelText(new RegExp(label)), { target: { value: 'x'.repeat(size) } });
    fireEvent.click(screen.getByRole('button', { name: 'Submit proof' }));
    expect(screen.getByText(`Use at most ${size - 1} characters.`)).toBeInTheDocument(); expect(save).not.toHaveBeenCalled();
  });
});
