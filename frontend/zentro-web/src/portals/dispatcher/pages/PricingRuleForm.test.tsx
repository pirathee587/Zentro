import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { server } from '@/shared/testing/mocks/server';
import { PricingRuleForm } from './PricingRuleForm';

const RULES = 'http://localhost:8080/api/v1/admin/pricing-rules';

const RULE = {
  id: '00000000-0000-0000-0000-000000000001',
  ruleName: 'STANDARD',
  baseFee: 550,
  includedWeightKg: 1,
  additionalWeightRatePerKg: 120,
  distanceSurchargeOtherDistrict: 402,
  expressSurcharge: 300,
  perKmRate: 0,
  currency: 'LKR',
  active: true,
  updatedAt: '2026-11-05T10:00:00Z',
};

/** Serves the rule; PUT stores the body so the next GET returns the saved values. */
function liveRule(putStatus?: number, putBody?: unknown) {
  let current: Record<string, unknown> = { ...RULE };
  const puts: unknown[] = [];
  server.use(
    http.get(RULES, () => HttpResponse.json(current)),
    http.put(RULES, async ({ request }) => {
      const body = await request.json();
      puts.push(body);
      if (putStatus === 0) return HttpResponse.error();
      if (putStatus) return HttpResponse.json(putBody ?? {}, { status: putStatus });
      current = { ...current, ...(body as object), updatedAt: '2026-11-06T09:00:00Z' };
      return HttpResponse.json(current);
    }),
  );
  return puts;
}

function renderForm() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false }, mutations: { retry: false } } });
  return render(<QueryClientProvider client={client}><PricingRuleForm /></QueryClientProvider>);
}

async function loaded() {
  return screen.findByLabelText('Base fee (LKR)');
}

function type(label: string, value: string) {
  fireEvent.change(screen.getByLabelText(label), { target: { value } });
}

const save = () => screen.getByRole('button', { name: 'Save changes' });

describe('PricingRuleForm', () => {
  it('shows the live rule with two decimals and nothing to save', async () => {
    liveRule();
    renderForm();

    expect(screen.getByRole('status')).toHaveTextContent('Loading the current pricing rule');
    expect(await loaded()).toHaveValue('550.00');
    expect(screen.getByLabelText('Included weight (kg)')).toHaveValue('1.00');
    expect(screen.getByLabelText('Rate per additional kg (LKR)')).toHaveValue('120.00');
    expect(screen.getByLabelText('Other-district surcharge (LKR)')).toHaveValue('402.00');
    expect(screen.getByLabelText('Express surcharge (LKR)')).toHaveValue('300.00');
    expect(screen.getByLabelText('Rate per km (same city) (LKR)')).toHaveValue('0.00');
    expect(screen.getByText('STANDARD')).toBeInTheDocument();
    expect(screen.getByText('No changes to save.')).toBeInTheDocument();
    expect(save()).toBeDisabled();
  });

  it('saves a valid change after confirmation, sends every amount and reloads the saved rule', async () => {
    const puts = liveRule();
    renderForm();
    await loaded();

    type('Base fee (LKR)', '600');
    type('Express surcharge (LKR)', '325.5');
    expect(screen.getByText('2 changes ready to save.')).toBeInTheDocument();
    fireEvent.click(save());

    const dialog = await screen.findByRole('dialog', { name: 'Save pricing changes?' });
    const baseRow = within(dialog).getByRole('row', { name: /Base fee/ });
    expect(baseRow).toHaveTextContent('LKR 550.00');
    expect(baseRow).toHaveTextContent('LKR 600.00');
    expect(within(dialog).getByRole('row', { name: /Express surcharge/ })).toHaveTextContent('LKR 325.50');
    expect(within(dialog).queryByRole('row', { name: /Included weight/ })).not.toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole('button', { name: 'Confirm and save' }));

    expect(await screen.findByRole('status')).toHaveTextContent('Pricing rule saved.');
    expect(puts).toEqual([{
      baseFee: 600, includedWeightKg: 1, additionalWeightRatePerKg: 120, distanceSurchargeOtherDistrict: 402,
      expressSurcharge: 325.5, perKmRate: 0, currency: 'LKR',
    }]);
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByLabelText('Base fee (LKR)')).toHaveValue('600.00'));
    expect(screen.getByLabelText('Express surcharge (LKR)')).toHaveValue('325.50');
    expect(save()).toBeDisabled();
  });

  it.each([
    ['Base fee (LKR)', '-1', 'Must be 0 or more.'],
    ['Base fee (LKR)', '', 'Enter a value.'],
    ['Included weight (kg)', 'abc', 'Enter a number.'],
    ['Included weight (kg)', '101', 'Must be at most 100.'],
    ['Rate per additional kg (LKR)', '12.345', 'Use at most 2 decimal places.'],
    ['Other-district surcharge (LKR)', '1000000.01', 'Must be at most 1,000,000.'],
    ['Express surcharge (LKR)', '-0.50', 'Must be 0 or more.'],
    ['Rate per km (same city) (LKR)', '1e3', 'Enter a number.'],
  ])('%s = "%s" shows "%s" and blocks saving', async (label, value, message) => {
    const puts = liveRule();
    renderForm();
    await loaded();

    type(label, value);

    const input = screen.getByLabelText(label);
    expect(input).toHaveAttribute('aria-invalid', 'true');
    expect(input).toHaveAccessibleDescription(message);
    expect(screen.getByText('Fix the highlighted fields to save.')).toBeInTheDocument();
    expect(save()).toBeDisabled();
    fireEvent.submit(screen.getByRole('form', { name: 'Pricing rule' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();
    expect(puts).toHaveLength(0);
  });

  it('typing the saved value again counts as unchanged', async () => {
    liveRule();
    renderForm();
    await loaded();

    type('Base fee (LKR)', '550');

    expect(screen.getByText('No changes to save.')).toBeInTheDocument();
    expect(save()).toBeDisabled();
  });

  it('cancelling the confirmation (button or Escape) sends nothing and keeps the typed values', async () => {
    const puts = liveRule();
    renderForm();
    await loaded();
    type('Base fee (LKR)', '600');

    fireEvent.click(save());
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Cancel' }));
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    fireEvent.click(save());
    fireEvent.keyDown(await screen.findByRole('dialog'), { key: 'Escape' });
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument();

    expect(puts).toHaveLength(0);
    expect(screen.getByLabelText('Base fee (LKR)')).toHaveValue('600');
  });

  it('the confirmation takes focus and returns it when closed', async () => {
    liveRule();
    renderForm();
    await loaded();
    type('Base fee (LKR)', '600');
    save().focus();

    fireEvent.click(save());
    const dialog = await screen.findByRole('dialog');
    expect(dialog).toHaveFocus();
    fireEvent.keyDown(dialog, { key: 'Escape' });

    expect(save()).toHaveFocus();
  });

  it('shows field errors from a 400 invalidFields response next to the fields', async () => {
    liveRule(400, {
      title: 'Bad Request', status: 400, detail: 'Validation failed for request.',
      invalidFields: { baseFee: 'must be greater than or equal to 0.00' },
    });
    renderForm();
    await loaded();
    type('Base fee (LKR)', '600');
    fireEvent.click(save());
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Confirm and save' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('The server did not accept some values.');
    const input = screen.getByLabelText('Base fee (LKR)');
    expect(input).toHaveAccessibleDescription('must be greater than or equal to 0.00');
    expect(input).toHaveValue('600');

    type('Base fee (LKR)', '601');
    expect(input).not.toHaveAccessibleDescription('must be greater than or equal to 0.00');
  });

  it('shows a permission message on 403 and keeps the values', async () => {
    liveRule(403);
    renderForm();
    await loaded();
    type('Base fee (LKR)', '600');
    fireEvent.click(save());
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Confirm and save' }));

    expect(await screen.findByRole('alert')).toHaveTextContent('Your account does not have permission to do that.');
    expect(screen.getByLabelText('Base fee (LKR)')).toHaveValue('600');
  });

  it('keeps the typed values after a network error so nothing is lost', async () => {
    liveRule(0);
    renderForm();
    await loaded();
    type('Base fee (LKR)', '600');
    type('Express surcharge (LKR)', '350');
    fireEvent.click(save());
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Confirm and save' }));

    expect(await screen.findByRole('alert')).toHaveTextContent(
      'We could not reach Zentro. Check your connection and try again. Your changes are kept.',
    );
    expect(screen.getByLabelText('Base fee (LKR)')).toHaveValue('600');
    expect(screen.getByLabelText('Express surcharge (LKR)')).toHaveValue('350');
    expect(save()).toBeEnabled();
  });

  it('Discard changes restores the saved values', async () => {
    liveRule();
    renderForm();
    await loaded();
    type('Base fee (LKR)', '-4');

    fireEvent.click(screen.getByRole('button', { name: 'Discard changes' }));

    expect(screen.getByLabelText('Base fee (LKR)')).toHaveValue('550.00');
    expect(screen.getByLabelText('Base fee (LKR)')).not.toHaveAttribute('aria-invalid');
  });

  it('a 403 while loading shows the permission message without a retry button', async () => {
    server.use(http.get(RULES, () => new HttpResponse(null, { status: 403 })));
    renderForm();

    expect(await screen.findByRole('alert')).toHaveTextContent('Your account does not have permission to do that.');
    expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
  });

  it('a 503 while loading shows the server message and can be retried', async () => {
    let calls = 0;
    server.use(http.get(RULES, () => {
      calls += 1;
      return calls === 1
        ? HttpResponse.json({ detail: 'Pricing data is temporarily unavailable. Please try again later.' }, { status: 503 })
        : HttpResponse.json(RULE);
    }));
    renderForm();

    expect(await screen.findByRole('alert')).toHaveTextContent('Pricing data is temporarily unavailable.');
    fireEvent.click(screen.getByRole('button', { name: 'Try again' }));

    expect(await loaded()).toHaveValue('550.00');
  });
});
