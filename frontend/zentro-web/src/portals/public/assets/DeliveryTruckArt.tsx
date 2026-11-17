import React from 'react';

interface DeliveryTruckArtProps {
  variant?: 'hero' | 'auth' | 'card';
  className?: string;
}

export function DeliveryTruckArt({ variant = 'hero', className = '' }: DeliveryTruckArtProps) {
  const isLight = variant === 'auth';

  return (
    <div className={`delivery-truck-stage ${variant} ${className}`} aria-label="Animated Zentro Delivery Van">
      <svg
        className="truck-svg"
        viewBox="0 0 640 280"
        fill="none"
        xmlns="http://www.w3.org/2000/svg"
        role="img"
      >
        <defs>
          {/* Gradients */}
          {/* Cargo Container Body: Clean crisp premium pearl white with soft shadow depth */}
          <linearGradient id={`vanBodyGrad_${variant}`} x1="0%" y1="0%" x2="0%" y2="100%">
            <stop offset="0%" stopColor="var(--white)" />
            <stop offset="40%" stopColor="var(--paper)" />
            <stop offset="85%" stopColor="var(--sand)" />
            <stop offset="100%" stopColor="var(--warm-pale)" />
          </linearGradient>

          {/* Cabin Body: Vibrant Automotive Golden-Amber */}
          <linearGradient id={`vanCabinGrad_${variant}`} x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="var(--gold)" />
            <stop offset="45%" stopColor="var(--ochre)" />
            <stop offset="100%" stopColor="var(--bronze)" />
          </linearGradient>

          <linearGradient id={`goldStripe_${variant}`} x1="0%" y1="0%" x2="100%" y2="0%">
            <stop offset="0%" stopColor="var(--gold)" />
            <stop offset="50%" stopColor="var(--gold-light)" />
            <stop offset="100%" stopColor="var(--ochre)" />
          </linearGradient>

          <linearGradient id={`windshieldGrad_${variant}`} x1="0%" y1="0%" x2="0%" y2="100%">
            <stop offset="0%" stopColor="var(--gold-light)" stopOpacity="0.85" />
            <stop offset="35%" stopColor="var(--dark-umber)" stopOpacity="0.95" />
            <stop offset="100%" stopColor="var(--espresso-deep)" stopOpacity="0.98" />
          </linearGradient>

          <linearGradient id={`headlightBeam_${variant}`} x1="0%" y1="50%" x2="100%" y2="50%">
            <stop offset="0%" stopColor="var(--cream)" stopOpacity="0.85" />
            <stop offset="35%" stopColor="var(--gold-light)" stopOpacity="0.35" />
            <stop offset="100%" stopColor="var(--gold-light)" stopOpacity="0" />
          </linearGradient>

          <filter id={`truckShadow_${variant}`} x="-15%" y="-15%" width="130%" height="150%">
            <feDropShadow dx="0" dy="14" stdDeviation="14" floodColor="var(--espresso-deep)" floodOpacity={isLight ? "0.18" : "0.35"} />
          </filter>
        </defs>

        {/* Headlight Projection Beam */}
        <path
          d="M482 176 L625 146 L630 228 L488 196 Z"
          fill={`url(#headlightBeam_${variant})`}
          className="headlight-beam-anim"
        />

        {/* ROAD AND SURFACE (y = 236 is the exact ground line) */}
        <g className="road-group">
          {/* Ground shadow beneath the van */}
          <ellipse cx="305" cy="238" rx="215" ry="10" fill="var(--espresso-deep)" opacity={isLight ? "0.18" : "0.55"} />

          {/* Road Asphalt Line */}
          <line
            x1="20"
            y1="236"
            x2="620"
            y2="236"
            stroke={isLight ? "var(--line)" : "var(--umber)"}
            strokeWidth="4"
            strokeLinecap="round"
          />

          {/* Animated Speed Lane Dashes on Road */}
          <line
            x1="20"
            y1="244"
            x2="620"
            y2="244"
            stroke={isLight ? "var(--ochre)" : "var(--gold)"}
            strokeWidth="3"
            strokeDasharray="16 20"
            strokeLinecap="round"
            className="road-dash-anim"
          />
        </g>

        {/* Animated Speed Wind Lines behind truck */}
        <g className="speed-lines-group">
          <line x1="95" y1="110" x2="45" y2="110" stroke={isLight ? "var(--ochre)" : "var(--gold)"} strokeWidth="2.5" strokeDasharray="10 14" className="speed-line speed-line-1" opacity="0.75" />
          <line x1="110" y1="135" x2="35" y2="135" stroke={isLight ? "var(--gold)" : "var(--gold-light)"} strokeWidth="2" strokeDasharray="14 18" className="speed-line speed-line-2" opacity="0.8" />
          <line x1="120" y1="160" x2="55" y2="160" stroke={isLight ? "var(--ochre)" : "var(--gold)"} strokeWidth="3" strokeDasharray="18 16" className="speed-line speed-line-3" opacity="0.65" />
        </g>

        {/* ======================================================== */}
        {/* TRUCK CHASSIS & WHEELS GROUP (Bounces gently as a unit) */}
        {/* ======================================================== */}
        <g className="truck-chassis-anim" filter={`url(#truckShadow_${variant})`}>
          {/* Main Cargo Box Body: top y=78, bottom y=208, left x=125, width=225 */}
          <rect
            x="125"
            y="78"
            width="225"
            height="130"
            rx="12"
            fill={`url(#vanBodyGrad_${variant})`}
            stroke="var(--line)"
            strokeWidth="2"
          />

          {/* Cabin Body: connects at x=350, extends forward to x=490, bottom y=208 */}
          <path
            d="M348 94 
               H408 
               C426 94 442 108 454 128 
               L478 162 
               C486 172 490 180 490 192 
               V208 
               H348 
               Z"
            fill={`url(#vanCabinGrad_${variant})`}
            stroke="var(--bronze)"
            strokeWidth="2"
          />

          {/* Windshield Glass */}
          <path
            d="M362 102 
               H404 
               C416 102 430 114 440 128 
               L458 156 
               H362 
               Z"
            fill={`url(#windshieldGrad_${variant})`}
            stroke="var(--dark-umber)"
            strokeWidth="1.5"
          />

          {/* Windshield Gloss Highlight */}
          <path d="M400 106 L388 152 H380 L392 106 Z" fill="var(--white)" opacity="0.35" />

          {/* Driver Silhouette */}
          <circle cx="396" cy="132" r="10" fill="var(--espresso-deep)" opacity="0.92" />
          <path d="M382 156 C382 144 390 140 402 140 C412 140 418 144 420 156 Z" fill="var(--espresso-deep)" opacity="0.92" />

          {/* Side Mirror */}
          <rect x="446" y="142" width="9" height="16" rx="3" fill="var(--ochre)" stroke="var(--bronze)" strokeWidth="1.2" />

          {/* Gold Streamline Accent Stripes on Cargo */}
          <rect x="125" y="152" width="223" height="11" fill={`url(#goldStripe_${variant})`} />
          <rect x="125" y="166" width="180" height="4" fill="var(--gold-light)" opacity="0.9" />

          {/* Zentro Express Branding on Cargo */}
          <g transform="translate(160, 96)">
            <circle cx="24" cy="24" r="20" fill="var(--espresso-deep)" />
            <circle cx="24" cy="24" r="17" fill="var(--espresso-deep)" />
            <text x="24" y="32" fontFamily="Georgia, serif" fontSize="22" fontWeight="bold" fill="var(--gold)" textAnchor="middle">z</text>
            <text x="54" y="24" fontFamily="'Plus Jakarta Sans', sans-serif" fontSize="18" fontWeight="800" fill="var(--espresso-deep)" letterSpacing="-0.5">zentro</text>
            <text x="55" y="37" fontFamily="'Plus Jakarta Sans', sans-serif" fontSize="8.5" fontWeight="800" fill="var(--ochre)" letterSpacing="1.6">EXPRESS</text>
          </g>

          {/* Cargo Door Seam */}
          <line x1="144" y1="78" x2="144" y2="208" stroke="var(--line)" strokeWidth="2" />
          <rect x="140" y="136" width="4" height="13" rx="2" fill="var(--ochre)" />

          {/* Front Bumper */}
          <path
            d="M478 198 H496 C498 198 500 200 500 203 V213 C500 215 498 217 496 217 H462 L470 198 Z"
            fill="var(--espresso-deep)"
            stroke="var(--bronze)"
            strokeWidth="1.5"
          />

          {/* Headlight Lamp */}
          <rect x="480" y="174" width="12" height="14" rx="4" fill="var(--cream)" />
          <circle cx="486" cy="181" r="4" fill="var(--gold)" />

          {/* Rear Taillight */}
          <rect x="121" y="176" width="5" height="16" rx="2" fill="var(--err)" />
          <rect x="122" y="178" width="3" height="6" rx="1" fill="var(--warm-pale)" />

          {/* =================================================== */}
          {/* WHEEL ARCH CUTOUTS: Bottom of chassis is y = 208    */}
          {/* Rear cutout center cx = 195, Front cutout cx = 425 */}
          {/* =================================================== */}
          <path d="M 160 208 A 35 35 0 0 1 230 208 Z" fill="var(--espresso-deep)" />
          <path d="M 390 208 A 35 35 0 0 1 460 208 Z" fill="var(--espresso-deep)" />

          {/* =================================================== */}
          {/* WHEELS: Center at cy = 208, radius = 28             */}
          {/* Bottom touches ground exactly at y = 208 + 28 = 236! */}
          {/* =================================================== */}

          {/* REAR WHEEL (cx = 195, cy = 208) */}
          <g transform="translate(195, 208)">
            {/* Outer Tire */}
            <circle cx="0" cy="0" r="28" fill="var(--espresso-deep)" stroke="var(--espresso-deep)" strokeWidth="2.5" />
            <circle cx="0" cy="0" r="24" fill="var(--espresso-deep)" />

            {/* Rotating Wheel Rim & Spokes: Center is (0,0) */}
            <g className="wheel-spin-anim">
              <animateTransform
                attributeName="transform"
                type="rotate"
                from="0 0 0"
                to="360 0 0"
                dur="0.8s"
                repeatCount="indefinite"
              />
              <circle cx="0" cy="0" r="17" fill="var(--umber)" stroke="var(--ochre)" strokeWidth="2" />
              <circle cx="0" cy="0" r="7" fill="var(--gold)" />
              {/* 6 Alloy Spokes */}
              <line x1="0" y1="-17" x2="0" y2="17" stroke="var(--gold)" strokeWidth="3" />
              <line x1="-14.7" y1="-8.5" x2="14.7" y2="8.5" stroke="var(--gold)" strokeWidth="3" />
              <line x1="-14.7" y1="8.5" x2="14.7" y2="-8.5" stroke="var(--gold)" strokeWidth="3" />
              <circle cx="0" cy="0" r="3.5" fill="var(--espresso-deep)" />
            </g>
          </g>

          {/* FRONT WHEEL (cx = 425, cy = 208) */}
          <g transform="translate(425, 208)">
            {/* Outer Tire */}
            <circle cx="0" cy="0" r="28" fill="var(--espresso-deep)" stroke="var(--espresso-deep)" strokeWidth="2.5" />
            <circle cx="0" cy="0" r="24" fill="var(--espresso-deep)" />

            {/* Rotating Wheel Rim & Spokes: Center is (0,0) */}
            <g className="wheel-spin-anim">
              <animateTransform
                attributeName="transform"
                type="rotate"
                from="0 0 0"
                to="360 0 0"
                dur="0.8s"
                repeatCount="indefinite"
              />
              <circle cx="0" cy="0" r="17" fill="var(--umber)" stroke="var(--ochre)" strokeWidth="2" />
              <circle cx="0" cy="0" r="7" fill="var(--gold)" />
              {/* 6 Alloy Spokes */}
              <line x1="0" y1="-17" x2="0" y2="17" stroke="var(--gold)" strokeWidth="3" />
              <line x1="-14.7" y1="-8.5" x2="14.7" y2="8.5" stroke="var(--gold)" strokeWidth="3" />
              <line x1="-14.7" y1="8.5" x2="14.7" y2="-8.5" stroke="var(--gold)" strokeWidth="3" />
              <circle cx="0" cy="0" r="3.5" fill="var(--espresso-deep)" />
            </g>
          </g>
        </g>

        {/* FLOATING TRANSIT BADGE (On the way: Colombo -> Islandwide) */}
        <g transform="translate(450, 48)" className="floating-badge-anim">
          <rect
            width="146"
            height="44"
            rx="22"
            fill={isLight ? "var(--white)" : "var(--espresso)"}
            stroke="var(--gold)"
            strokeWidth="1.8"
            filter={`url(#truckShadow_${variant})`}
          />
          {/* Live pulsing beacon */}
          <circle cx="20" cy="22" r="5" fill="var(--gold)" className="pulse-dot-anim" />
          <circle cx="20" cy="22" r="9" fill="var(--gold)" opacity="0.3" className="pulse-ring-anim" />
          <text
            x="34"
            y="19"
            fontFamily="'Plus Jakarta Sans', sans-serif"
            fontSize="10"
            fontWeight="700"
            fill={isLight ? "var(--espresso)" : "var(--white)"}
          >
            In Express Transit
          </text>
          <text
            x="34"
            y="31"
            fontFamily="'Plus Jakarta Sans', sans-serif"
            fontSize="8"
            fontWeight="600"
            fill="var(--ochre)"
          >
            Colombo → Islandwide
          </text>
        </g>
      </svg>
    </div>
  );
}
