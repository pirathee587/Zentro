import { useEffect, useRef, type RefObject } from 'react';

/** Shared focus behavior for existing modal presentations; does not decide when actions may close. */
export function useDialogFocus(ref: RefObject<HTMLElement>, onEscape: () => void) {
  const escape = useRef(onEscape);
  escape.current = onEscape;
  useEffect(() => {
    const dialog = ref.current;
    if (!dialog) return;
    const previous = document.activeElement as HTMLElement | null;
    dialog.focus();
    function keydown(event: KeyboardEvent) {
      if (event.key === 'Escape') { event.preventDefault(); escape.current(); }
      if (event.key !== 'Tab' || !dialog) return;
      const controls = [...dialog.querySelectorAll<HTMLElement>('a[href], button, input, select, textarea, [tabindex]')]
        .filter(control => !control.matches(':disabled, [tabindex="-1"], [hidden]') && !control.closest('[hidden], [aria-hidden="true"]')
          && getComputedStyle(control).display !== 'none' && getComputedStyle(control).visibility !== 'hidden');
      const first = controls[0], last = controls[controls.length - 1];
      if (!first) { event.preventDefault(); dialog.focus(); }
      else if (event.shiftKey && (document.activeElement === first || document.activeElement === dialog)) { event.preventDefault(); last.focus(); }
      else if (!event.shiftKey && (document.activeElement === last || document.activeElement === dialog)) { event.preventDefault(); first.focus(); }
    }
    dialog.addEventListener('keydown', keydown);
    return () => { dialog.removeEventListener('keydown', keydown); if (previous?.isConnected) previous.focus(); };
  }, [ref]);
}
