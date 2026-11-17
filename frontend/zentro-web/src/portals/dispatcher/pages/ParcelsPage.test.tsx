import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { ParcelsPage } from './ParcelsPage';

describe('Parcels page presentation', () => {
  it('keeps search and filters working together', () => {
    render(<ParcelsPage />);
    fireEvent.click(screen.getByRole('tab', { name: 'Delivered' }));
    expect(screen.getByRole('tab', { name: 'Delivered' })).toHaveAttribute('aria-selected', 'true');
    const table = screen.getByRole('table');
    expect(within(table).getAllByRole('row')).toHaveLength(4);
    fireEvent.change(screen.getByRole('textbox', { name: 'Search by reference or place' }), {
      target: { value: 'Moratuwa' },
    });
    expect(within(table).getAllByRole('row')).toHaveLength(2);
    expect(table).toHaveTextContent('Moratuwa');
  });
  it('marks the selected reference and shows its existing details', () => {
    render(<ParcelsPage />);
    const table = screen.getByRole('table');
    const row = within(table).getAllByRole('row')[8];
    const reference = within(row).getByRole('button');
    fireEvent.click(reference);
    expect(reference).toHaveAttribute('aria-pressed', 'true');
    expect(reference).toHaveAttribute('aria-controls', 'parcel-detail-panel');
    const detail = screen.getByRole('region', { name: 'Parcel detail' });
    expect(detail).toHaveTextContent(reference.textContent!);
    expect(within(detail).getByText('Size')).toBeInTheDocument();
    expect(within(detail).getByText('Weight')).toBeInTheDocument();
  });
  it('identifies the current timeline step without inventing event timestamps', () => {
    render(<ParcelsPage />);
    const table = screen.getByRole('table');
    const row = within(table)
      .getAllByRole('row')
      .find((item) => within(item).queryByText('In transit'))!;
    fireEvent.click(row);
    const timeline = screen.getByRole('list', { name: 'Delivery progress' });
    const current = within(timeline)
      .getAllByRole('listitem')
      .find((item) => item.getAttribute('aria-current') === 'step');
    expect(current).toHaveTextContent('In transit');
    expect(current).toHaveTextContent('Current');
    expect(screen.getByText(/Event timestamps are unavailable/)).toBeInTheDocument();
  });
  it('clearly identifies local preview updates and links to live assignment', () => {
    render(<ParcelsPage />);
    expect(screen.getByRole('complementary', { name: 'Preview notice' })).toHaveTextContent('not saved to the backend');
    expect(screen.getByRole('link', { name: /live Dispatch Queue/ })).toHaveAttribute('href', '/dispatcher/queue');
    fireEvent.click(screen.getByRole('button', { name: 'Update preview' }));
    expect(screen.getByRole('status')).toHaveTextContent('No changes saved.');
    fireEvent.change(screen.getByRole('textbox', { name: 'Search by reference or place' }), { target: { value: 'no matching parcel' } });
    expect(screen.getByRole('table')).toHaveTextContent('No parcels match your filters and search.');
  });});
