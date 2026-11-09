import { useRef, useState } from 'react';
import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { useDialogFocus } from './useDialogFocus';
function Dialog({ close, empty = false }: { close: () => void; empty?: boolean }) {
  const ref = useRef<HTMLDivElement>(null);
  useDialogFocus(ref, close);
  return <div role="dialog" aria-label="Synthetic dialog" ref={ref} tabIndex={-1}>
    {!empty && <><button onClick={close}>Cancel</button><button disabled>Unavailable</button><button>Confirm</button></>}
  </div>;
}
function Example({ empty = false }: { empty?: boolean }) {
  const [open, setOpen] = useState(false);
  return <><button onClick={() => setOpen(true)}>Open</button>{open && <Dialog empty={empty} close={() => setOpen(false)} />}</>;
}
describe('modal keyboard focus', () => {
  it('wraps Tab and Shift+Tab, skips disabled controls, closes with Escape and restores focus', async () => {
    const user = userEvent.setup(); render(<Example />);
    const trigger = screen.getByRole('button', { name: 'Open' }); await user.tab(); await user.keyboard('{Enter}');
    expect(screen.getByRole('dialog')).toHaveFocus();
    await user.tab(); expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
    await user.tab({ shift: true }); expect(screen.getByRole('button', { name: 'Confirm' })).toHaveFocus();
    await user.tab(); expect(screen.getByRole('button', { name: 'Cancel' })).toHaveFocus();
    await user.keyboard('{Escape}'); expect(trigger).toHaveFocus();
  });
  it('keeps an empty dialog focused until Escape releases it', async () => {
    const user = userEvent.setup(); render(<Example empty />);
    await user.tab(); await user.keyboard('{Enter}'); await user.tab(); expect(screen.getByRole('dialog')).toHaveFocus();
    await user.keyboard('{Escape}'); expect(screen.getByRole('button', { name: 'Open' })).toHaveFocus();
  });
});
