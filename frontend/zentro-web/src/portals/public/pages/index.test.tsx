import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it, vi } from 'vitest';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import { HomePage } from './index';

describe('public landing page', () => {
  it('does not show Team or Contact navigation', () => {
    render(<MemoryRouter><HomePage /></MemoryRouter>);
    expect(screen.getByRole('navigation', { name: 'Main navigation' })).not.toHaveTextContent(/team|contact/i);
  });
  it('rejects an invalid tracking number', () => {
    render(<MemoryRouter><HomePage /></MemoryRouter>);
    fireEvent.change(screen.getByLabelText('Tracking number'), { target: { value: 'ZTR-ABCDEFG231' } });
    fireEvent.click(screen.getByRole('button', { name: /track parcel/i }));
    expect(screen.getByRole('alert')).toHaveTextContent('Enter a valid tracking number');
  });
  it('accepts the required alphabet and routes valid numbers', () => {
    render(<MemoryRouter initialEntries={['/']}><Routes><Route path="/" element={<HomePage />} /><Route path="/track/:trackingNumber" element={<p>Tracking route opened</p>} /></Routes></MemoryRouter>);
    fireEvent.change(screen.getByLabelText('Tracking number'), { target: { value: 'ZTR-ABCDEFG234' } });
    fireEvent.click(screen.getByRole('button', { name: /track parcel/i }));
    expect(screen.getByText('Tracking route opened')).toBeInTheDocument();
  });
  it('shows reveal content without movement when reduced motion is preferred', () => {
    vi.stubGlobal('matchMedia', vi.fn(() => ({ matches: true })));
    const { container } = render(<MemoryRouter><HomePage /></MemoryRouter>);
    expect(container.querySelector('.reveal')?.classList.contains('is-visible')).toBe(true);
    vi.unstubAllGlobals();
  });
});
