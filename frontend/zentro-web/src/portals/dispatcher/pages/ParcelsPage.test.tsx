import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { ParcelsPage, ParcelItem } from './ParcelsPage';

/** Fixture parcels — same 16 items the old INITIAL_PARCELS used so assertions still match. */
const FIXTURE_PARCELS: ParcelItem[] = [
  { reference: 'ZT-7K2M9QX4PA', route: 'Colombo 03 to Kandy', status: 'Unassigned', courier: 'None', weightCategory: 'Medium', weightKg: '2 kg' },
  { reference: 'ZT-3HD8V1NC6R', route: 'Galle to Matara', status: 'Unassigned', courier: 'None', weightCategory: 'Small', weightKg: '0.8 kg' },
  { reference: 'ZT-9WQ4T7BE2X', route: 'Negombo to Colombo 07', status: 'Unassigned', courier: 'None', weightCategory: 'Large', weightKg: '5 kg' },
  { reference: 'ZT-5RN2K8YD1V', route: 'Kandy to Nuwara Eliya', status: 'Unassigned', courier: 'None', weightCategory: 'Small', weightKg: '1.5 kg' },
  { reference: 'ZT-2XB6M4QH8C', route: 'Colombo 02 to Gampaha', status: 'Unassigned', courier: 'None', weightCategory: 'Medium', weightKg: '3 kg' },
  { reference: 'ZT-8FT1P5ZK3J', route: 'Colombo 05 to Dehiwala', status: 'Assigned', courier: 'Saman Perera', weightCategory: 'Small', weightKg: '1 kg' },
  { reference: 'ZT-4CJ9W2LR7N', route: 'Colombo 07 to Maharagama', status: 'Assigned', courier: 'Kumari Silva', weightCategory: 'Medium', weightKg: '2.5 kg' },
  { reference: 'ZT-6MV3D8HA9E', route: 'Kandy to Peradeniya', status: 'Picked up', courier: 'Ruwan Jayasinghe', weightCategory: 'Small', weightKg: '1.2 kg' },
  { reference: 'ZT-10K7S4TB5G', route: 'Galle to Hikkaduwa', status: 'In transit', courier: 'Dilani Fernando', weightCategory: 'Medium', weightKg: '3.4 kg' },
  { reference: 'ZT-9DP5X1NW2Y', route: 'Colombo 03 to Nugegoda', status: 'In transit', courier: 'Saman Perera', weightCategory: 'Large', weightKg: '4.5 kg' },
  { reference: 'ZT-3ZG8R6CE4U', route: 'Colombo 04 to Piliyandala', status: 'Out for delivery', courier: 'Kumari Silva', weightCategory: 'Small', weightKg: '0.9 kg' },
  { reference: 'ZT-7HN2L9VM6F', route: 'Colombo 01 to Kottawa', status: 'Out for delivery', courier: 'Saman Perera', weightCategory: 'Medium', weightKg: '2.1 kg' },
  { reference: 'ZT-2YB4F8KQ1D', route: 'Colombo 06 to Moratuwa', status: 'Delivered', courier: 'Kumari Silva', weightCategory: 'Medium', weightKg: '2 kg' },
  { reference: 'ZT-8RC1W5NT3M', route: 'Kandy to Katugastota', status: 'Delivered', courier: 'Ruwan Jayasinghe', weightCategory: 'Small', weightKg: '1.5 kg' },
  { reference: 'ZT-5GD9Z2XH7B', route: 'Galle to Unawatuna', status: 'Delivered', courier: 'Dilani Fernando', weightCategory: 'Small', weightKg: '0.8 kg' },
  { reference: 'ZT-4TK603MV9E', route: 'Colombo 05 to Ratmalana', status: 'Failed', courier: 'Saman Perera', weightCategory: 'Medium', weightKg: '3 kg' },
];

describe('Parcels page presentation', () => {
  it('keeps search and filters working together', () => {
    render(<ParcelsPage initialParcels={FIXTURE_PARCELS} />);
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
    render(<ParcelsPage initialParcels={FIXTURE_PARCELS} />);
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
    render(<ParcelsPage initialParcels={FIXTURE_PARCELS} />);
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
    render(<ParcelsPage initialParcels={FIXTURE_PARCELS} />);
    expect(screen.getByRole('complementary', { name: 'Preview notice' })).toHaveTextContent('not saved to the backend');
    expect(screen.getByRole('link', { name: /live Dispatch Queue/ })).toHaveAttribute('href', '/dispatcher/queue');
    fireEvent.click(screen.getByRole('button', { name: 'Update preview' }));
    expect(screen.getByRole('status')).toHaveTextContent('No changes saved.');
    fireEvent.change(screen.getByRole('textbox', { name: 'Search by reference or place' }), { target: { value: 'no matching parcel' } });
    expect(screen.getByRole('table')).toHaveTextContent('No parcels match your filters and search.');
  });
});
