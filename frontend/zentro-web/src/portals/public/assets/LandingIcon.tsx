export type LandingIconName = 'route' | 'heart' | 'pin' | 'clock' | 'package' | 'receipt' | 'wallet' | 'sparkle';

export function LandingIcon({ name }: { name: LandingIconName }) {
  const icons = {
    route: <><path d="M4 19c4-1 4-7 8-8s4-5 8-6"/><circle cx="4" cy="19" r="1.5"/><circle cx="20" cy="5" r="1.5"/></>,
    heart: <path d="M20.8 8.8c0 4.2-8.8 10-8.8 10s-8.8-5.8-8.8-10a4.5 4.5 0 0 1 8.8-1.3 4.5 4.5 0 0 1 8.8 1.3Z"/>,
    pin: <><path d="M19 10c0 5-7 11-7 11S5 15 5 10a7 7 0 1 1 14 0Z"/><circle cx="12" cy="10" r="2"/></>,
    clock: <><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></>,
    package: <><path d="m12 3 9 5-9 5-9-5 9-5Z"/><path d="M3 8v9l9 5 9-5V8M12 13v9"/></>,
    receipt: <><path d="M6 3h12v18l-3-2-3 2-3-2-3 2V3Z"/><path d="M9 8h6M9 12h6"/></>,
    wallet: <><rect x="3" y="6" width="18" height="14" rx="2"/><path d="M3 9h18M16 14h2M6 6V4h12"/></>,
    sparkle: <><path d="m12 3 1.5 6.5L20 12l-6.5 1.5L12 20l-1.5-6.5L4 12l6.5-2.5L12 3Z"/><path d="m19 15 .8 2.2L22 18l-2.2.8L19 21l-.8-2.2L16 18l2.2-.8L19 15Z"/></>,
  };

  return <svg className="landing-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">{icons[name]}</svg>;
}
