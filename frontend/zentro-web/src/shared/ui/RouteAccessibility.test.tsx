import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Link, Route, Routes } from 'react-router-dom';
import { RouteAccessibility } from './RouteAccessibility';

it('provides a skip link and focuses main on route changes with non-personal page titles', async () => {
  const user = userEvent.setup();
  render(<MemoryRouter initialEntries={['/customer/history']}><RouteAccessibility portal="Customer dashboard" />
    <Routes><Route path="*" element={<main><h1>Synthetic account name</h1><Link to="/customer/track/PRIVATE-ID">Track parcel</Link></main>} /></Routes>
  </MemoryRouter>);
  expect(document.title).toBe('Delivery history | Zentro');
  expect(screen.getByRole('main')).toHaveFocus();
  expect(screen.getByRole('link', { name: 'Skip to main content' })).toHaveAttribute('href', '#main-content');
  await user.click(screen.getByRole('link', { name: 'Track parcel' }));
  expect(document.title).toBe('Track a parcel | Zentro');
  expect(document.title).not.toMatch(/PRIVATE|Synthetic/);
  expect(screen.getByRole('main')).toHaveFocus();
});
describe('landing page anchor', () => {
  it('preserves the existing home id for skip navigation', () => {
    render(<MemoryRouter><RouteAccessibility portal="Home" /><main id="home"><h1>Home</h1></main></MemoryRouter>);
    expect(screen.getByRole('link', { name: 'Skip to main content' })).toHaveAttribute('href', '#home');
  });
});
