export function PhonePreview() {
  return (
    <svg className="phone-preview" viewBox="0 0 300 470" role="img" aria-labelledby="phone-preview-title">
      <title id="phone-preview-title">Illustration of the Zentro parcel tracking screen</title>
      <rect x="14" y="10" width="272" height="450" rx="38" fill="var(--paper)" stroke="var(--umber)" strokeWidth="10" />
      <rect x="112" y="19" width="76" height="14" rx="7" fill="var(--umber)" />
      <circle cx="53" cy="66" r="15" fill="var(--espresso)" /><text x="53" y="72" textAnchor="middle" fill="var(--gold-light)" fontFamily="Georgia,serif" fontSize="20">z</text>
      <text x="78" y="69" fill="var(--ink)" fontFamily="sans-serif" fontWeight="600" fontSize="11">Your delivery</text>
      <text x="247" y="68" fill="var(--stone)" fontFamily="sans-serif" fontSize="16">•••</text>
      <rect x="35" y="95" width="230" height="225" rx="19" fill="var(--sand)" />
      <text x="54" y="126" fill="var(--stone)" fontFamily="sans-serif" fontWeight="700" fontSize="9" letterSpacing="1.2">PARCEL STATUS</text>
      <text x="54" y="158" fill="var(--espresso)" fontFamily="sans-serif" fontWeight="700" fontSize="21">On the way</text>
      <path d="M63 199h171" stroke="var(--gold-light)" strokeWidth="4" strokeLinecap="round" />
      <circle cx="63" cy="199" r="8" fill="var(--umber)" stroke="var(--paper)" strokeWidth="3" /><circle cx="120" cy="199" r="5" fill="var(--ochre)" /><circle cx="177" cy="199" r="5" fill="var(--ochre)" /><circle cx="234" cy="199" r="10" fill="var(--gold)" stroke="var(--paper)" strokeWidth="3" />
      <text x="54" y="224" fill="var(--muted)" fontFamily="sans-serif" fontSize="9">Colombo</text><text x="205" y="224" fill="var(--muted)" fontFamily="sans-serif" fontSize="9">Kandy</text>
      <path d="M54 244h192" stroke="var(--line)" strokeWidth="1" />
      <circle cx="69" cy="276" r="15" fill="var(--gold-light)" /><text x="69" y="280" textAnchor="middle" fill="var(--umber)" fontFamily="sans-serif" fontWeight="700" fontSize="10">Z</text>
      <text x="94" y="273" fill="var(--ink)" fontFamily="sans-serif" fontWeight="700" fontSize="10">Courier partner</text><text x="94" y="289" fill="var(--stone)" fontFamily="sans-serif" fontSize="8">Delivery update</text>
      <rect x="35" y="338" width="230" height="67" rx="15" fill="var(--paper)" stroke="var(--line)" />
      <circle cx="64" cy="371" r="14" fill="var(--warm-pale)" /><path d="m58 371 4 4 8-9" fill="none" stroke="var(--bronze)" strokeWidth="2.5" strokeLinecap="round" strokeLinejoin="round" />
      <text x="89" y="369" fill="var(--ink)" fontFamily="sans-serif" fontWeight="700" fontSize="10">Live delivery updates</text><text x="89" y="385" fill="var(--stone)" fontFamily="sans-serif" fontSize="8">Follow the journey</text>
      <rect x="120" y="435" width="60" height="4" rx="2" fill="var(--line)" />
    </svg>
  );
}
