import { render, screen, fireEvent, waitFor, cleanup } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { MemoryRouter } from 'react-router-dom';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import { NotificationBell, NotificationHistory } from './NotificationBell';
import { notificationApi } from './api';
import { useAuth } from '@/shared/auth/useAuth';
import { useInboxStream } from './useInboxStream';
import type { Role } from '@/shared/config/roles';

vi.mock('@/shared/auth/useAuth', () => ({ useAuth: vi.fn() }));
vi.mock('./api', () => ({ notificationApi: { list: vi.fn(), read: vi.fn(), readAll: vi.fn() } }));
vi.mock('./useInboxStream', () => ({ useInboxStream: vi.fn() }));
function auth(role: Role, id = 'owner') {
  vi.mocked(useAuth).mockReturnValue({ user: { id, accessToken: 'synthetic', roles: [role] } } as ReturnType<typeof useAuth>);
}
function mount(ui: React.ReactElement) {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(<QueryClientProvider client={client}><MemoryRouter>{ui}</MemoryRouter></QueryClientProvider>);
}
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(notificationApi.list).mockResolvedValue({ page: 0, total: 1, unread: 1, items: [{
    id: 'note', parcelId: 'parcel', type: 'DELIVERED', title: 'Parcel delivered', message: 'Parcel delivered.',
    target: '/customer/history', occurredAt: '2026-11-22T10:00:00Z', readAt: null,
  }] });
  vi.mocked(notificationApi.read).mockResolvedValue(); vi.mocked(notificationApi.readAll).mockResolvedValue();
});
afterEach(cleanup);
describe('notification portals', () => {
  it.each<Role>(['CUSTOMER', 'MERCHANT', 'COURIER', 'DISPATCHER'])('loads the %s inbox and read actions', async role => {
    auth(role); mount(<NotificationBell portal={role} />);
    fireEvent.click(await screen.findByRole('button', { name: 'View notifications, 1 unread' }));
    expect(await screen.findByText('Parcel delivered')).toBeInTheDocument();
    expect(notificationApi.list).toHaveBeenCalledWith(role, 0, 'synthetic', expect.any(AbortSignal));
    fireEvent.click(screen.getByRole('button', { name: 'Mark all as read' }));
    await waitFor(() => expect(notificationApi.readAll).toHaveBeenCalledWith(role, 'synthetic'));
    fireEvent.click(screen.getByRole('button', { name: /Parcel delivered Unread/ }));
    await waitFor(() => expect(notificationApi.read).toHaveBeenCalledWith(role, 'note', 'synthetic'));
  });
  it('refreshes the unread badge when a stream snapshot arrives', async () => {
    auth('CUSTOMER'); mount(<NotificationBell portal="CUSTOMER" />);
    await screen.findByRole('button', { name: 'View notifications, 1 unread' });
    vi.mocked(notificationApi.list).mockResolvedValue({ page: 0, items: [], unread: 0, total: 0 });
    const callback = vi.mocked(useInboxStream).mock.calls.at(-1)![2];
    callback();
    await screen.findByRole('button', { name: 'View notifications' });
  });
  it('isolates a mismatched portal and supports empty history', async () => {
    auth('CUSTOMER'); mount(<NotificationBell portal="MERCHANT" />);
    expect(notificationApi.list).not.toHaveBeenCalled();
    cleanup();
    vi.mocked(notificationApi.list).mockResolvedValue({ page: 0, items: [], unread: 0, total: 0 });
    mount(<NotificationHistory portal="CUSTOMER" />);
    expect(await screen.findByText('No notifications yet.')).toBeInTheDocument();
  });
  it('shows a recoverable history error', async () => {
    auth('CUSTOMER'); vi.mocked(notificationApi.list).mockRejectedValue(new Error('offline'));
    mount(<NotificationHistory portal="CUSTOMER" />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Could not load notifications');
    expect(screen.getByRole('button', { name: 'Try again' })).toBeInTheDocument();
  });
  it('shows unavailable without automatic retries or opening a stream', async () => {
    auth('CUSTOMER');
    vi.mocked(notificationApi.list).mockRejectedValue({ isAxiosError: true, response: { status: 503 } });
    mount(<NotificationBell portal="CUSTOMER" />);
    await waitFor(() => expect(notificationApi.list).toHaveBeenCalledTimes(1));
    fireEvent.click(screen.getByRole('button', { name: 'View notifications' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Notifications are not available yet.');
    expect(notificationApi.list).toHaveBeenCalledTimes(1);
    expect(vi.mocked(useInboxStream).mock.calls.at(-1)?.[1]).toBeUndefined();
    vi.mocked(notificationApi.list).mockResolvedValue({ page: 0, total: 0, unread: 0, items: [] });
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    expect(await screen.findByText('No notifications yet.')).toBeInTheDocument();
  });
});
