import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/shared/testing/mocks/server';
import { QuotePreview } from './QuotePreview';

const API = 'http://localhost:8080';
const ZONES = [
  { id: 'z1', code: 'ZONE_COLOMBO_METRO', name: 'Colombo Metro', description: null, active: true },
  { id: 'z2', code: 'ZONE_CENTRAL', name: 'Central', description: null, active: true },
];

function mockApi(estimate: (body: Record<string, unknown>) => Response) {
  const requests: Record<string, unknown>[] = [];
  server.use(
    http.get(`${API}/api/v1/zones`, () => HttpResponse.json(ZONES)),
    http.post(`${API}/api/v1/parcels/estimate`, async ({ request }) => {
      const body = (await request.json()) as Record<string, unknown>;
      requests.push(body);
      return estimate(body);
    }),
  );
  return requests;
}

function renderPreview() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(<QueryClientProvider client={client}><QuotePreview currency="LKR" /></QueryClientProvider>);
}

const calculate = () => fireEvent.click(screen.getByRole('button', { name: 'Calculate fee' }));

describe('QuotePreview', () => {
  it('same city 1 kg: sends the request and shows the fee, days and only the lines the API returned', async () => {
    const requests = mockApi(() => HttpResponse.json({
      fee: 550, formattedFee: 'LKR 550', currency: 'LKR', estimatedDays: 2,
      breakdown: { baseFee: 550, weightFee: 0 },
    }));
    renderPreview();

    calculate();

    const result = await screen.findByRole('region', { name: 'Quoted fee' });
    expect(requests).toEqual([{
      destinationType: 'SAME_CITY', parcelSize: 'SMALL', weightKg: 1, isExpress: false, currency: 'LKR',
    }]);
    expect(within(result).getByText('Estimated delivery: 2 days')).toBeInTheDocument();
    expect(within(result).getByText('Base fee').nextSibling).toHaveTextContent('LKR 550.00');
    expect(within(result).getByText('Weight fee').nextSibling).toHaveTextContent('LKR 0.00');
    expect(within(result).queryByText('Distance surcharge')).not.toBeInTheDocument();
    expect(within(result).queryByText('Express fee')).not.toBeInTheDocument();
    expect(within(result).getByText('Total').nextSibling).toHaveTextContent('LKR 550.00');
  });

  it('other district 2.5 kg express with zones: every line uses the same two-decimal money format', async () => {
    const requests = mockApi(() => HttpResponse.json({
      fee: 1432, formattedFee: 'LKR 1,432', currency: 'LKR', estimatedDays: 1,
      breakdown: { baseFee: 550, weightFee: 180, distanceSurcharge: 402, expressFee: 300 },
    }));
    renderPreview();
    await screen.findAllByRole('option', { name: 'Central (ZONE_CENTRAL)' });

    fireEvent.change(screen.getByLabelText('Destination'), { target: { value: 'OTHER_DISTRICT' } });
    fireEvent.change(screen.getByLabelText('Parcel size'), { target: { value: 'MEDIUM' } });
    fireEvent.change(screen.getByLabelText('Weight (kg)'), { target: { value: '2.5' } });
    fireEvent.click(screen.getByLabelText('Express delivery'));
    fireEvent.change(screen.getByLabelText('Origin zone (optional)'), { target: { value: 'ZONE_COLOMBO_METRO' } });
    fireEvent.change(screen.getByLabelText('Destination zone (optional)'), { target: { value: 'ZONE_CENTRAL' } });
    calculate();

    const result = await screen.findByRole('region', { name: 'Quoted fee' });
    expect(requests[0]).toEqual({
      destinationType: 'OTHER_DISTRICT', parcelSize: 'MEDIUM', weightKg: 2.5, isExpress: true, currency: 'LKR',
      originZone: 'ZONE_COLOMBO_METRO', destinationZone: 'ZONE_CENTRAL',
    });
    expect(within(result).getByText('Estimated delivery: 1 day')).toBeInTheDocument();
    expect(within(result).getByText('Weight fee').nextSibling).toHaveTextContent('LKR 180.00');
    expect(within(result).getByText('Distance surcharge').nextSibling).toHaveTextContent('LKR 402.00');
    expect(within(result).getByText('Express fee').nextSibling).toHaveTextContent('LKR 300.00');
    expect(within(result).getAllByText('LKR 1,432.00')).toHaveLength(2);
    expect(result).not.toHaveTextContent('LKR 1,432 ');
  });

  it.each([
    ['0', 'Weight must be between 0.1 and 20 kg.'],
    ['25', 'Weight must be between 0.1 and 20 kg.'],
    ['2.555', 'Use at most 2 decimal places.'],
    ['', 'Enter the parcel weight.'],
    ['two', 'Enter a number.'],
  ])('weight "%s" shows "%s" and sends no request', async (weight, message) => {
    const requests = mockApi(() => HttpResponse.json({}));
    renderPreview();

    fireEvent.change(screen.getByLabelText('Weight (kg)'), { target: { value: weight } });
    calculate();

    expect(screen.getByLabelText('Weight (kg)')).toHaveAccessibleDescription(message);
    expect(screen.getByLabelText('Weight (kg)')).toHaveAttribute('aria-invalid', 'true');
    await new Promise((resolve) => setTimeout(resolve, 20));
    expect(requests).toHaveLength(0);
    expect(screen.queryByRole('region', { name: 'Quoted fee' })).not.toBeInTheDocument();
  });

  it('shows API field errors next to the inputs (for example a SAME_CITY route across zones)', async () => {
    mockApi(() => HttpResponse.json({
      status: 400, detail: 'Unsupported route and zone combination: SAME_CITY cannot span different delivery zones.',
      invalidFields: { destinationType: 'Unsupported route and zone combination: SAME_CITY cannot span different delivery zones.' },
    }, { status: 400 }));
    renderPreview();
    await screen.findAllByRole('option', { name: 'Central (ZONE_CENTRAL)' });
    fireEvent.change(screen.getByLabelText('Origin zone (optional)'), { target: { value: 'ZONE_COLOMBO_METRO' } });
    fireEvent.change(screen.getByLabelText('Destination zone (optional)'), { target: { value: 'ZONE_CENTRAL' } });

    calculate();

    expect(await screen.findByRole('alert')).toHaveTextContent('Check the highlighted fields.');
    expect(screen.getByLabelText('Destination')).toHaveAccessibleDescription(
      'Unsupported route and zone combination: SAME_CITY cannot span different delivery zones.',
    );
  });

  it('shows a clear message when the pricing service is unavailable, and clears it when inputs change', async () => {
    mockApi(() => HttpResponse.json(
      { status: 503, detail: 'Pricing service is temporarily unavailable. Please try again later.' }, { status: 503 },
    ));
    renderPreview();

    calculate();

    expect(await screen.findByRole('alert')).toHaveTextContent('Pricing service is temporarily unavailable.');
    fireEvent.change(screen.getByLabelText('Weight (kg)'), { target: { value: '2' } });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
    expect(screen.getByLabelText('Weight (kg)')).toHaveValue('2');
  });

  it('shows the permission message on 403', async () => {
    mockApi(() => new HttpResponse(null, { status: 403 }));
    renderPreview();

    calculate();

    expect(await screen.findByRole('alert')).toHaveTextContent('Your account does not have permission to do that.');
  });

  it('still works without zones when the zone list cannot be loaded', async () => {
    server.use(
      http.get(`${API}/api/v1/zones`, () => new HttpResponse(null, { status: 500 })),
      http.post(`${API}/api/v1/parcels/estimate`, () => HttpResponse.json({
        fee: 550, formattedFee: 'LKR 550', currency: 'LKR', estimatedDays: 2, breakdown: { baseFee: 550, weightFee: 0 },
      })),
    );
    renderPreview();

    expect(await screen.findByText('Zones could not be loaded, so the preview runs without zones.')).toBeInTheDocument();
    expect(screen.getByLabelText('Origin zone (optional)')).toBeDisabled();
    calculate();
    expect(await screen.findByRole('region', { name: 'Quoted fee' })).toHaveTextContent('LKR 550.00');
  });
});
