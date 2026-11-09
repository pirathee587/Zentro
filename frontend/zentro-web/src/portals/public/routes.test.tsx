import { render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { MemoryRouter, useRoutes } from 'react-router-dom';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { portalRoutes } from './routes';

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
});
