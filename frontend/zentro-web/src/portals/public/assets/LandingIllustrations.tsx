import React from 'react';

/**
 * Animated Sri Lanka Logistics & Route Map Illustration for "Why Zentro" section
 * Seamlessly color-matched to `.why-section`'s warm sand background (var(--cream)).
 */
export function SriLankaRouteMap() {
  return (
    <div className="interactive-map-art" role="img" aria-label="Zentro Sri Lanka Delivery Network Map">
      <svg viewBox="0 0 540 380" fill="none" xmlns="http://www.w3.org/2000/svg" className="sl-map-svg">
        <defs>
          {/* Card background gradient matched to var(--cream) */}
          <linearGradient id="mapLightBg" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="var(--paper)" />
            <stop offset="100%" stopColor="var(--warm-pale)" />
          </linearGradient>

          <linearGradient id="routeGoldLight" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="var(--gold)" />
            <stop offset="50%" stopColor="var(--bronze)" />
            <stop offset="100%" stopColor="var(--bronze)" />
          </linearGradient>

          <filter id="lightCardShadow" x="-10%" y="-10%" width="120%" height="120%">
            <feDropShadow dx="0" dy="12" stdDeviation="16" floodColor="var(--umber)" floodOpacity="0.08" />
          </filter>
        </defs>

        {/* Card Background: light warm cream, harmonizing perfectly with var(--cream) */}
        <rect
          width="540"
          height="380"
          rx="22"
          fill="url(#mapLightBg)"
          stroke="var(--line)"
          strokeWidth="1.5"
          filter="url(#lightCardShadow)"
        />

        {/* Elegant Coordinates Grid Lines */}
        <g stroke="var(--line)" strokeWidth="1" strokeDasharray="3 6" opacity="0.6">
          <line x1="40" y1="90" x2="500" y2="90" />
          <line x1="40" y1="180" x2="500" y2="180" />
          <line x1="40" y1="270" x2="500" y2="270" />
          <line x1="140" y1="30" x2="140" y2="350" />
          <line x1="270" y1="30" x2="270" y2="350" />
          <line x1="400" y1="30" x2="400" y2="350" />
        </g>

        {/* Stylized Island Silhouette - Warm Sand Tint */}
        <path
          d="M260 48
             C275 65 292 95 296 130
             C302 180 320 220 310 270
             C300 310 270 338 245 342
             C215 345 195 315 190 280
             C182 230 188 170 205 120
             C218 85 240 52 260 48 Z"
          fill="var(--line)"
          stroke="var(--stone)"
          strokeWidth="2"
        />

        {/* Animated Connected Express Routes */}
        {/* Jaffna (top) to Kandy (center) */}
        <path
          d="M262 70 Q250 130 268 185"
          stroke="url(#routeGoldLight)"
          strokeWidth="3"
          strokeDasharray="6 8"
          className="route-flow-anim"
          fill="none"
        />

        {/* Kandy (center) to Colombo (west) */}
        <path
          d="M268 185 Q235 200 206 226"
          stroke="url(#routeGoldLight)"
          strokeWidth="4"
          className="route-flow-active"
          fill="none"
        />

        {/* Colombo (west) to Galle (south) */}
        <path
          d="M206 226 Q215 270 228 312"
          stroke="url(#routeGoldLight)"
          strokeWidth="3"
          strokeDasharray="6 8"
          className="route-flow-anim"
          fill="none"
        />

        {/* Kandy to Trincomalee / Batticaloa (east) */}
        <path
          d="M268 185 Q300 170 312 145"
          stroke="var(--bronze)"
          strokeWidth="2.5"
          strokeDasharray="5 7"
          className="route-flow-anim"
          fill="none"
        />

        {/* CITY HUBS / WAYPOINTS */}
        {/* Jaffna */}
        <g transform="translate(262, 70)">
          <circle cx="0" cy="0" r="12" fill="var(--ochre)" opacity="0.25" className="pulse-hub" />
          <circle cx="0" cy="0" r="5" fill="var(--bronze)" />
          <text x="14" y="4" fill="var(--umber)" fontSize="11" fontWeight="700" fontFamily="'Plus Jakarta Sans', sans-serif">Jaffna</text>
        </g>

        {/* Trincomalee */}
        <g transform="translate(312, 145)">
          <circle cx="0" cy="0" r="4" fill="var(--bronze)" />
          <text x="12" y="4" fill="var(--stone)" fontSize="10" fontWeight="600" fontFamily="'Plus Jakarta Sans', sans-serif">Trincomalee</text>
        </g>

        {/* Kandy Central Logistics Hub */}
        <g transform="translate(268, 185)">
          <circle cx="0" cy="0" r="18" fill="var(--ochre)" opacity="0.22" className="pulse-hub-large" />
          <circle cx="0" cy="0" r="8" fill="var(--espresso)" stroke="var(--gold)" strokeWidth="2.5" />
          <circle cx="0" cy="0" r="3" fill="var(--gold)" />
          <text x="16" y="5" fill="var(--espresso)" fontSize="12" fontWeight="800" fontFamily="'Plus Jakarta Sans', sans-serif">Kandy Hub</text>
        </g>

        {/* Colombo Headquarters / Main Dispatch */}
        <g transform="translate(206, 226)">
          <circle cx="0" cy="0" r="22" fill="var(--ochre)" opacity="0.25" className="pulse-hub-large" />
          <circle cx="0" cy="0" r="10" fill="var(--espresso)" stroke="var(--gold)" strokeWidth="2.5" />
          <circle cx="0" cy="0" r="4" fill="var(--gold)" />
          {/* Badge */}
          <rect x="-86" y="-12" width="72" height="24" rx="12" fill="var(--espresso)" />
          <text x="-50" y="4" fill="var(--gold)" fontSize="10" fontWeight="800" textAnchor="middle" fontFamily="'Plus Jakarta Sans', sans-serif">COLOMBO</text>
        </g>

        {/* Galle */}
        <g transform="translate(228, 312)">
          <circle cx="0" cy="0" r="12" fill="var(--ochre)" opacity="0.25" className="pulse-hub" />
          <circle cx="0" cy="0" r="5" fill="var(--bronze)" />
          <text x="14" y="4" fill="var(--umber)" fontSize="11" fontWeight="700" fontFamily="'Plus Jakarta Sans', sans-serif">Galle</text>
        </g>

        {/* FLOATING DELIVERY STATS CARD */}
        <g transform="translate(345, 235)" className="floating-metric-card">
          <rect width="170" height="88" rx="16" fill="var(--white)" stroke="var(--sand)" strokeWidth="1.5" />
          <circle cx="24" cy="26" r="9" fill="var(--gold)" opacity="0.18" />
          <circle cx="24" cy="26" r="4.5" fill="var(--gold)" />
          <text x="42" y="24" fill="var(--espresso)" fontSize="11" fontWeight="700" fontFamily="'Plus Jakarta Sans', sans-serif">Active Dispatch</text>
          <text x="42" y="37" fill="var(--bronze)" fontSize="9" fontWeight="600" fontFamily="'Plus Jakarta Sans', sans-serif">All 25 Districts Connected</text>
          <line x1="16" y1="48" x2="154" y2="48" stroke="var(--cream)" strokeWidth="1" />
          <text x="16" y="66" fill="var(--stone)" fontSize="9" fontFamily="'Plus Jakarta Sans', sans-serif">Doorstep delivery rate</text>
          <text x="16" y="78" fill="var(--espresso)" fontSize="11" fontWeight="700" fontFamily="'Plus Jakarta Sans', sans-serif">Same-day · 4.9★ SLA</text>
        </g>
      </svg>
    </div>
  );
}

/**
 * Animated Business Fulfillment & Dispatch Hub Illustration for "For Businesses" section
 * Seamlessly color-matched to `.business-section`'s clean white background (var(--white)).
 */
export function BusinessHubIllustration() {
  return (
    <div className="business-hub-art" role="img" aria-label="Zentro Merchant Automation & Fulfillment Hub">
      <svg viewBox="0 0 540 360" fill="none" xmlns="http://www.w3.org/2000/svg" className="biz-hub-svg">
        <defs>
          {/* Card background gradient matched to white section */}
          <linearGradient id="bizLightBg" x1="0%" y1="0%" x2="100%" y2="100%">
            <stop offset="0%" stopColor="var(--paper)" />
            <stop offset="100%" stopColor="var(--cream)" />
          </linearGradient>

          <linearGradient id="scannerLaserRed" x1="0%" y1="0%" x2="0%" y2="100%">
            <stop offset="0%" stopColor="var(--err)" stopOpacity="0.85" />
            <stop offset="100%" stopColor="var(--err)" stopOpacity="0.1" />
          </linearGradient>

          <filter id="bizCardShadow" x="-10%" y="-10%" width="120%" height="120%">
            <feDropShadow dx="0" dy="12" stdDeviation="16" floodColor="var(--umber)" floodOpacity="0.08" />
          </filter>
        </defs>

        {/* Base Background Card: soft warm off-white, blends seamlessly into white background */}
        <rect
          width="540"
          height="360"
          rx="22"
          fill="url(#bizLightBg)"
          stroke="var(--sand)"
          strokeWidth="1.5"
          filter="url(#bizCardShadow)"
        />

        {/* Fulfillment Conveyor Belt / Platform */}
        <rect x="40" y="235" width="460" height="26" rx="13" fill="var(--dark-umber)" stroke="var(--umber)" strokeWidth="2" />
        <line x1="50" y1="248" x2="490" y2="248" stroke="var(--gold)" strokeWidth="2" strokeDasharray="16 16" className="road-dash-anim" />

        {/* PARCEL BOX 1 (Large Main Zentro Parcel) */}
        <g transform="translate(180, 125)">
          {/* Main Box Face */}
          <rect width="140" height="110" rx="10" fill="var(--gold-light)" stroke="var(--bronze)" strokeWidth="2" />
          {/* Package Sealing Tape */}
          <rect x="52" y="0" width="36" height="110" fill="var(--gold)" />
          <line x1="70" y1="0" x2="70" y2="110" stroke="var(--bronze)" strokeWidth="1.5" strokeDasharray="4 4" />
          {/* Zentro Stamp */}
          <circle cx="70" cy="55" r="20" fill="var(--espresso-deep)" />
          <text x="70" y="62" fontFamily="Georgia, serif" fontSize="20" fontWeight="bold" fill="var(--gold)" textAnchor="middle">z</text>
          {/* Barcode Label */}
          <rect x="18" y="22" width="30" height="24" rx="4" fill="var(--white)" />
          <line x1="22" y1="26" x2="22" y2="42" stroke="var(--espresso-deep)" strokeWidth="1.5" />
          <line x1="26" y1="26" x2="26" y2="42" stroke="var(--espresso-deep)" strokeWidth="1" />
          <line x1="30" y1="26" x2="30" y2="42" stroke="var(--espresso-deep)" strokeWidth="2" />
          <line x1="35" y1="26" x2="35" y2="42" stroke="var(--espresso-deep)" strokeWidth="1.5" />
          <line x1="41" y1="26" x2="41" y2="42" stroke="var(--espresso-deep)" strokeWidth="2" />
          {/* Express logistics label */}
          <text x="70" y="96" fontFamily="'Plus Jakarta Sans', sans-serif" fontSize="7.5" fontWeight="800" fill="var(--bronze)" textAnchor="middle" letterSpacing="1.2">EXPRESS LOGISTICS</text>
        </g>

        {/* PARCEL BOX 2 (Medium Kraft Box) */}
        <g transform="translate(80, 155)">
          <rect width="90" height="80" rx="8" fill="var(--gold)" stroke="var(--bronze)" strokeWidth="1.5" />
          <rect x="32" y="0" width="24" height="80" fill="var(--gold)" />
          <rect x="12" y="18" width="20" height="16" rx="3" fill="var(--white)" />
          <line x1="16" y1="22" x2="16" y2="30" stroke="var(--espresso-deep)" strokeWidth="1" />
          <line x1="20" y1="22" x2="20" y2="30" stroke="var(--espresso-deep)" strokeWidth="2" />
          <line x1="25" y1="22" x2="25" y2="30" stroke="var(--espresso-deep)" strokeWidth="1" />
        </g>

        {/* AUTOMATED LASER BARCODE SCANNER BEAM */}
        <g className="laser-scanner-group">
          <rect x="220" y="45" width="60" height="18" rx="8" fill="var(--espresso-deep)" stroke="var(--gold)" strokeWidth="1.5" />
          <circle cx="250" cy="54" r="4" fill="var(--err)" />
          {/* Scanning Laser Beam projecting onto parcel */}
          <polygon points="230,63 270,63 325,180 175,180" fill="url(#scannerLaserRed)" className="laser-beam-anim" />
          {/* Laser Line on Box */}
          <line x1="180" y1="180" x2="320" y2="180" stroke="var(--err)" strokeWidth="2.5" className="laser-line-anim" />
        </g>

        {/* FLOATING MERCHANT API & WEBHOOK BADGE */}
        <g transform="translate(340, 70)" className="floating-metric-card">
          <rect width="170" height="98" rx="16" fill="var(--white)" stroke="var(--line)" strokeWidth="1.5" />
          {/* REST API Badge */}
          <rect x="16" y="16" width="56" height="20" rx="10" fill="var(--espresso)" />
          <text x="44" y="30" fill="var(--gold)" fontSize="10" fontWeight="800" textAnchor="middle" fontFamily="'Plus Jakarta Sans', sans-serif">REST API</text>
          {/* Webhook live check */}
          <circle cx="145" cy="26" r="6" fill="var(--gold)" />
          <text x="16" y="56" fill="var(--espresso)" fontSize="12" fontWeight="700" fontFamily="'Plus Jakarta Sans', sans-serif">Signed Webhooks</text>
          <text x="16" y="72" fill="var(--bronze)" fontSize="9" fontWeight="600" fontFamily="'Plus Jakarta Sans', sans-serif">Instant order dispatch</text>
          <text x="16" y="86" fill="var(--stone)" fontSize="8" fontFamily="'Plus Jakarta Sans', sans-serif">HMAC-SHA256 verified</text>
        </g>

        {/* Bottom Platform Status Pill */}
        <g transform="translate(185, 290)">
          <rect width="170" height="34" rx="17" fill="var(--white)" stroke="var(--line)" strokeWidth="1.2" />
          <circle cx="22" cy="17" r="5" fill="var(--gold)" className="pulse-hub" />
          <text x="36" y="21" fill="var(--espresso)" fontSize="10" fontWeight="700" fontFamily="'Plus Jakarta Sans', sans-serif">99.98% On-Time SLA</text>
        </g>
      </svg>
    </div>
  );
}
