import { useCallback, useId, useRef, useState } from 'react';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { isAxiosError } from 'axios';
import { Link, useNavigate } from 'react-router-dom';
import { Bell, CheckCircle, AlertTriangle, Package } from 'lucide-react';
import { Button } from '@/shared/ui';
import { useAuth } from '@/shared/auth/useAuth';
import { principalCacheScope } from '@/shared/api/queryKeys';
import type { Role } from '@/shared/config/roles';
import { notificationApi, type NotificationItem } from './api';
import { useInboxStream } from './useInboxStream';
import './notifications.css';

const home = (portal: Role) => portal === 'MERCHANT' ? '/merchant/dashboard' : `/${portal.toLowerCase()}`;
const unavailable = (error: unknown) => isAxiosError(error) && error.response?.status === 503;

function useInbox(portal: Role, page: number) {
  const { user } = useAuth();
  const client = useQueryClient();
  const scope = principalCacheScope(user?.id);
  const prefix = ['notifications', scope, portal] as const;
  const token = user?.accessToken;
  const allowed = !!token && !!user?.roles.includes(portal);
  const query = useQuery({
    queryKey: [...prefix, page],
    queryFn: ({ signal }) => notificationApi.list(portal, page, token!, signal),
    enabled: allowed, staleTime: 10000,
    retry: false, retryOnMount: false,
    refetchInterval: state => allowed && !unavailable(state.state.error) ? 30000 : false,
    refetchOnMount: state => !unavailable(state.state.error),
    refetchOnWindowFocus: state => !unavailable(state.state.error),
  });
  const update = useCallback(() => {
    void client.invalidateQueries({ queryKey: ['notifications', scope, portal] });
  }, [client, scope, portal]);
  const read = useMutation({ mutationFn: (id: string) => notificationApi.read(portal, id, token!), onSuccess: update });
  const readAll = useMutation({ mutationFn: () => notificationApi.readAll(portal, token!), onSuccess: update });
  return { query, update, read, readAll, token: allowed && query.isSuccess ? token : undefined };
}

function NotificationList({ portal, history = false, close }: { portal: Role; history?: boolean; close?: () => void }) {
  const [page, setPage] = useState(0);
  const { query, read, readAll } = useInbox(portal, page);
  const navigate = useNavigate();
  const open = async (item: NotificationItem) => {
    try {
      await read.mutateAsync(item.id);
      // Navigation is restricted to this portal even if a malformed stored target is returned.
      if (item.target.startsWith(`${home(portal)}/`)) { navigate(item.target); close?.(); }
    } catch { /* The mutation error is displayed below. */ }
  };
  return <>
    <div className="zentro-inbox-heading"><h2>{history ? 'Notification history' : 'Notifications'}</h2>
      <Button onClick={() => readAll.mutate()} disabled={!query.data?.unread || readAll.isPending}>Mark all as read</Button></div>
    {query.isPending && <p role="status">Loading notifications…</p>}
    {query.isError && <p role="alert">{unavailable(query.error) ? 'Notifications are not available yet.' : 'Could not load notifications.'} <Button onClick={() => void query.refetch()}>Try again</Button></p>}
    {(read.isError || readAll.isError) && <p role="alert">Could not mark notifications as read. Please try again.</p>}
    {query.data?.items.length === 0 && <p>No notifications yet.</p>}
    <ul className="zentro-inbox-list">{query.data?.items.map(item => <li key={item.id} className={item.readAt ? '' : 'zentro-inbox-unread'}>
      <Button type="button" onClick={() => void open(item)} disabled={read.isPending}>
        <span aria-hidden="true">{item.type.includes('FAILED') || item.type.includes('REJECTED') || item.type.includes('BLOCKED')
          ? <AlertTriangle size={18} /> : item.type.includes('DELIVERED') || item.type.includes('COMPLETED') ? <CheckCircle size={18} /> : <Package size={18} />}</span>
        <span><strong>{item.title}</strong>{!item.readAt && <span className="zentro-inbox-new"> Unread</span>}
          <span className="zentro-inbox-message">{item.message}</span>
          <time dateTime={item.occurredAt}>{new Date(item.occurredAt).toLocaleString()}</time></span>
      </Button>
    </li>)}</ul>
    {history ? <nav aria-label="Notification pages"><Button disabled={page === 0} onClick={() => setPage(p => p - 1)}>Previous</Button>
      <span> Page {page + 1} </span><Button disabled={!query.data || (page + 1) * 20 >= query.data.total} onClick={() => setPage(p => p + 1)}>Next</Button></nav>
      : <Link to={`${home(portal)}/notifications`} onClick={close}>View all notifications</Link>}
  </>;
}

export function NotificationBell({ portal }: { portal: Role }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLDivElement>(null);
  const id = useId();
  const { query, token, update } = useInbox(portal, 0);
  useInboxStream(portal, token, update);
  return <div ref={ref} className="zentro-inbox" onBlur={event => {
    if (!event.currentTarget.contains(event.relatedTarget)) setOpen(false);
  }} onKeyDown={event => {
    if (event.key === 'Escape') { setOpen(false); ref.current?.querySelector('button')?.focus(); }
  }}>
    <Button type="button" className="sd-icon-btn" aria-label={`View notifications${query.data?.unread ? `, ${query.data.unread} unread` : ''}`}
      aria-expanded={open} aria-controls={id} title="Notifications" onClick={() => setOpen(v => !v)}>
      <Bell size={20} aria-hidden="true" />{!!query.data?.unread && <span className="zentro-inbox-badge" aria-hidden="true">{query.data.unread > 99 ? '99+' : query.data.unread}</span>}
    </Button>
    {open && <section id={id} aria-label="Notifications" className="zentro-inbox-panel"><NotificationList portal={portal} close={() => setOpen(false)} /></section>}
  </div>;
}

export function NotificationHistory({ portal }: { portal: Role }) {
  return <section className="zentro-inbox-history" aria-label="Notification history"><NotificationList portal={portal} history /></section>;
}
