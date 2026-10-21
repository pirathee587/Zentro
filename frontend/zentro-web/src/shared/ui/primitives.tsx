import type { PropsWithChildren, ReactNode } from 'react';
export function Card({children}:PropsWithChildren) { return <section className="rounded-lg border bg-surface p-4 shadow-sm">{children}</section>; }
export function Spinner() { return <span aria-label="Loading" className="inline-block h-5 w-5 animate-spin rounded-full border-2 border-primary border-t-transparent" />; }
export function EmptyState({message='Nothing here yet'}:{message?:string}) { return <p className="text-muted">{message}</p>; }
export function ErrorState({message='Something went wrong'}:{message?:string}) { return <p role="alert" className="text-danger">{message}</p>; }
export function Toast({children}:PropsWithChildren) { return <div role="status" className="rounded-md bg-text px-3 py-2 text-surface">{children}</div>; }
export function Modal({open,children}:{open:boolean;children:ReactNode}) { return open ? <div role="dialog" aria-modal="true" className="rounded-lg border bg-surface p-4">{children}</div> : null; }
export function Table({children}:PropsWithChildren) { return <div className="overflow-x-auto"><table className="w-full text-left">{children}</table></div>; }
export function Pagination() { return <nav aria-label="Pagination" className="text-sm text-muted">Pagination placeholder</nav>; }
