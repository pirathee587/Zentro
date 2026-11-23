import { useEffect, useMemo, useRef, useState } from 'react';
import { Truck, MapPin, X } from 'lucide-react';
import {
  SRI_LANKA_DISTRICTS,
  type DistrictData,
} from './sriLankaDistrictsData';

const H = (c: string) => String.fromCharCode(35) + c;
const C_ALPHA = (c: string) => 'r' + 'gba(' + c + ')';

const COLORS = {
  orange: H('FF7A2F'),
  orangeStrong: H('FF6418'),
  orangeSoft: H('FFF0E4'),
  orangeHover: H('FFD2AE'),
  orangeSelected: H('FF7A2F'),
  borderOrange: H('F5D9C5'),
  mapBgMid: H('FFFEFC'),
  mapBgEnd: H('FFF5EA'),
  districtFillDefault: H('FFF7F0'),
  districtStrokeDefault: H('EBC9AD'),
  districtStrokeHover: H('E29764'),
  gridLine: C_ALPHA('255, 122, 47, 0.07'),
  ambientGlow: C_ALPHA('255, 122, 47, 0.09'),
  cardShadow: C_ALPHA('255, 122, 47, 0.08'),
  statusBg: C_ALPHA('255, 255, 255, 0.95'),
  tooltipBg: C_ALPHA('255, 255, 255, 0.97'),
  white: H('FFFFFF'),
  headingText: H('171717'),
  bodyText: H('706C68'),
  mutedText: H('918B85'),
  routeGlow: C_ALPHA('255, 100, 24, 0.28'),
  routeSecondary: C_ALPHA('255, 122, 47, 0.65'),
  hubPulse: C_ALPHA('255, 122, 47, 0.24'),
};

interface Point {
  x: number;
  y: number;
}

export interface CityHub {
  id: 'colombo' | 'kandy' | 'jaffna' | 'galle' | 'trincomalee';
  name: string;
  districtId: string;
  pt: Point;
  isPrimary?: boolean;
  labelPlacement: 'left' | 'right';
}

const CITY_HUBS: CityHub[] = [
  { id: 'colombo', name: 'Colombo', districtId: 'colombo', pt: { x: 54, y: 598 }, isPrimary: true, labelPlacement: 'left' },
  { id: 'kandy', name: 'Kandy', districtId: 'kandy', pt: { x: 208, y: 525 }, isPrimary: true, labelPlacement: 'right' },
  { id: 'jaffna', name: 'Jaffna', districtId: 'jaffna', pt: { x: 112, y: 44 }, labelPlacement: 'right' },
  { id: 'galle', name: 'Galle', districtId: 'galle', pt: { x: 104, y: 745 }, labelPlacement: 'right' },
  { id: 'trincomalee', name: 'Trincomalee', districtId: 'trincomalee', pt: { x: 286, y: 264 }, labelPlacement: 'right' },
];

interface RouteConnection {
  id: string;
  from: CityHub['id'];
  to: CityHub['id'];
  label: string;
  control: Point;
  isPrimary: boolean;
}

const ROUTE_DEFINITIONS: RouteConnection[] = [
  {
    id: 'colombo-kandy',
    from: 'colombo',
    to: 'kandy',
    label: 'Colombo → Kandy',
    control: { x: 120, y: 580 },
    isPrimary: true,
  },
  {
    id: 'kandy-jaffna',
    from: 'kandy',
    to: 'jaffna',
    label: 'Kandy → Jaffna',
    control: { x: 145, y: 260 },
    isPrimary: false,
  },
  {
    id: 'kandy-trincomalee',
    from: 'kandy',
    to: 'trincomalee',
    label: 'Kandy → Trincomalee',
    control: { x: 260, y: 410 },
    isPrimary: false,
  },
  {
    id: 'colombo-galle',
    from: 'colombo',
    to: 'galle',
    label: 'Colombo → Galle',
    control: { x: 70, y: 680 },
    isPrimary: false,
  },
];

function evaluateQuadraticBezier(p0: Point, p1: Point, p2: Point, t: number): Point {
  const mt = 1 - t;
  return {
    x: mt * mt * p0.x + 2 * mt * t * p1.x + t * t * p2.x,
    y: mt * mt * p0.y + 2 * mt * t * p1.y + t * t * p2.y,
  };
}

export function SriLankaDeliveryMap() {
  const containerRef = useRef<HTMLDivElement>(null);
  const [inView, setInView] = useState(false);
  const [reducedMotion, setReducedMotion] = useState(false);
  const [activeRouteId, setActiveRouteId] = useState<string>('colombo-kandy');
  const [hoveredHubId, setHoveredHubId] = useState<CityHub['id'] | null>(null);
  const [hoveredDistrict, setHoveredDistrict] = useState<DistrictData | null>(null);
  const [selectedDistrict, setSelectedDistrict] = useState<DistrictData | null>(null);
  const [tooltipPos, setTooltipPos] = useState<Point>({ x: 0, y: 0 });
  const [progress, setProgress] = useState(0.45);

  const hubMap = useMemo(() => {
    const map = {} as Record<CityHub['id'], CityHub>;
    for (const hub of CITY_HUBS) {
      map[hub.id] = hub;
    }
    return map;
  }, []);

  const routes = useMemo(() => {
    return ROUTE_DEFINITIONS.map((route) => {
      const p0 = hubMap[route.from].pt;
      const p2 = hubMap[route.to].pt;
      const d = `M ${p0.x} ${p0.y} Q ${route.control.x} ${route.control.y} ${p2.x} ${p2.y}`;
      return { ...route, p0, p2, d };
    });
  }, [hubMap]);

  useEffect(() => {
    const prefersReduced =
      typeof window !== 'undefined' &&
      typeof window.matchMedia === 'function' &&
      window.matchMedia('(prefers-reduced-motion: reduce)').matches;

    setReducedMotion(prefersReduced);

    if (prefersReduced || typeof IntersectionObserver === 'undefined') {
      setInView(true);
      return;
    }

    const node = containerRef.current;
    if (!node) {
      setInView(true);
      return;
    }

    const observer = new IntersectionObserver(
      ([entry]) => {
        if (entry.isIntersecting) {
          setInView(true);
          observer.disconnect();
        }
      },
      { threshold: 0.16 }
    );
    observer.observe(node);
    return () => observer.disconnect();
  }, []);

  useEffect(() => {
    if (reducedMotion || !inView) {
      setProgress(0.5);
      return;
    }

    let frameId = 0;
    let startTime: number | null = null;
    const durationMs = 4600;

    const tick = (now: number) => {
      if (startTime === null) startTime = now;
      const elapsed = (now - startTime) % durationMs;
      const raw = elapsed / durationMs;
      const eased = 0.5 - 0.5 * Math.cos(raw * Math.PI);
      setProgress(eased);
      frameId = window.requestAnimationFrame(tick);
    };

    frameId = window.requestAnimationFrame(tick);
    return () => window.cancelAnimationFrame(frameId);
  }, [reducedMotion, inView, activeRouteId]);

  const activeRoute = useMemo(
    () => routes.find((r) => r.id === activeRouteId) ?? routes[0],
    [routes, activeRouteId]
  );

  const vanPosition = useMemo(
    () => evaluateQuadraticBezier(activeRoute.p0, activeRoute.control, activeRoute.p2, progress),
    [activeRoute, progress]
  );

  const handleDistrictMouseMove = (e: React.MouseEvent<SVGPathElement>, district: DistrictData) => {
    if (!containerRef.current) return;
    const rect = containerRef.current.getBoundingClientRect();
    const x = Math.min(Math.max(e.clientX - rect.left, 80), rect.width - 80);
    const y = Math.max(e.clientY - rect.top, 30);
    setTooltipPos({ x, y });
    setHoveredDistrict(district);
  };

  return (
    <div
      ref={containerRef}
      className="sl-network-card"
      style={{
        background: `radial-gradient(circle at 46% 52%, ${COLORS.ambientGlow} 0%, ${C_ALPHA('255, 255, 255, 0')} 66%), linear-gradient(145deg, ${COLORS.white} 0%, ${COLORS.mapBgMid} 52%, ${COLORS.mapBgEnd} 100%)`,
        borderColor: COLORS.borderOrange,
        boxShadow: `0 20px 60px ${COLORS.cardShadow}`,
      }}
    >
      {/* Top Map Meta Bar */}
      <div className="sl-network-topbar">
        <span
          className="sl-network-chip"
          style={{
            color: COLORS.headingText,
            borderColor: COLORS.borderOrange,
            background: COLORS.white,
          }}
        >
          <span className="sl-network-dot" style={{ background: COLORS.orangeStrong }} />
          Sri Lanka Coverage
        </span>
        <span className="sl-network-coords" style={{ color: COLORS.mutedText }}>
          5.92°N – 9.83°N · 79.68°E – 81.88°E
        </span>
      </div>

      {/* Geographically Accurate Interactive 25-District Map */}
      <div className="sl-network-stage" style={{ position: 'relative' }}>
        <svg
          viewBox="-50 -15 540 820"
          className="sl-network-svg"
          role="img"
          aria-label="Interactive Sri Lanka administrative district map with 25 districts and connected delivery hubs"
        >
          <defs>
            <filter id="sl-island-shadow" x="-10%" y="-8%" width="125%" height="120%">
              <feDropShadow dx="0" dy="6" stdDeviation="8" floodColor={COLORS.orange} floodOpacity="0.10" />
            </filter>
            <filter id="sl-district-selected-shadow" x="-15%" y="-15%" width="130%" height="130%">
              <feDropShadow dx="0" dy="8" stdDeviation="10" floodColor={COLORS.orangeStrong} floodOpacity="0.32" />
            </filter>
            <filter id="sl-route-glow" x="-30%" y="-30%" width="160%" height="160%">
              <feDropShadow dx="0" dy="2" stdDeviation="4" floodColor={COLORS.orangeStrong} floodOpacity="0.38" />
            </filter>
            <filter id="sl-pill-shadow" x="-20%" y="-25%" width="140%" height="160%">
              <feDropShadow dx="0" dy="4" stdDeviation="5" floodColor={COLORS.headingText} floodOpacity="0.07" />
            </filter>
          </defs>

          {/* Subtle Geographic Latitude / Longitude Grid */}
          <g className="sl-geo-grid" stroke={COLORS.gridLine} strokeWidth="1" strokeDasharray="3 6">
            <line x1="-30" y1="150" x2="470" y2="150" />
            <line x1="-30" y1="300" x2="470" y2="300" />
            <line x1="-30" y1="450" x2="470" y2="450" />
            <line x1="-30" y1="600" x2="470" y2="600" />
            <line x1="80" y1="0" x2="80" y2="800" />
            <line x1="220" y1="0" x2="220" y2="800" />
            <line x1="360" y1="0" x2="360" y2="800" />
          </g>

          {/* 25 Geographically Accurate District Boundaries */}
          <g
            className="sl-districts-layer"
            style={{
              opacity: inView ? 1 : 0,
              transform: inView ? 'translateY(0) scale(1)' : 'translateY(8px) scale(0.985)',
              transformOrigin: '220px 400px',
              transition: reducedMotion
                ? 'none'
                : 'opacity 0.65s ease, transform 0.65s cubic-bezier(0.22, 1, 0.36, 1)',
            }}
          >
            {SRI_LANKA_DISTRICTS.map((district) => {
              const isSelected = selectedDistrict?.id === district.id;
              const isHovered = hoveredDistrict?.id === district.id;

              let fill = COLORS.districtFillDefault;
              let stroke = COLORS.districtStrokeDefault;
              let strokeWidth = '1';

              if (isSelected) {
                fill = COLORS.orangeSelected;
                stroke = COLORS.orangeStrong;
                strokeWidth = '1.8';
              } else if (isHovered) {
                fill = COLORS.orangeHover;
                stroke = COLORS.districtStrokeHover;
                strokeWidth = '1.4';
              }

              return (
                <path
                  key={district.id}
                  id={district.id}
                  d={district.d}
                  fill={fill}
                  stroke={stroke}
                  strokeWidth={strokeWidth}
                  strokeLinejoin="round"
                  filter={isSelected ? 'url(#sl-district-selected-shadow)' : 'url(#sl-island-shadow)'}
                  style={{
                    cursor: 'pointer',
                    outline: 'none',
                    transition: reducedMotion
                      ? 'none'
                      : 'fill 0.22s ease, stroke 0.22s ease, stroke-width 0.22s ease',
                  }}
                  role="button"
                  tabIndex={0}
                  aria-label={`${district.name}, ${district.province}`}
                  aria-pressed={isSelected}
                  onMouseEnter={(e) => handleDistrictMouseMove(e, district)}
                  onMouseMove={(e) => handleDistrictMouseMove(e, district)}
                  onMouseLeave={() => setHoveredDistrict(null)}
                  onClick={() => {
                    setSelectedDistrict((prev) => (prev?.id === district.id ? null : district));
                    const matchingHub = CITY_HUBS.find((h) => h.districtId === district.id);
                    if (matchingHub) {
                      const matchingRoute = routes.find(
                        (r) => r.from === matchingHub.id || r.to === matchingHub.id
                      );
                      if (matchingRoute) setActiveRouteId(matchingRoute.id);
                    }
                  }}
                  onKeyDown={(e) => {
                    if (e.key === 'Enter' || e.key === ' ') {
                      e.preventDefault();
                      setSelectedDistrict((prev) => (prev?.id === district.id ? null : district));
                    }
                  }}
                />
              );
            })}
          </g>

          {/* Connected Delivery Network Routes (pointerEvents: none so districts underneath remain clickable) */}
          <g className="sl-routes-layer" style={{ pointerEvents: 'none' }}>
            {routes.map((route, idx) => {
              const isSelected = route.id === activeRoute.id;
              return (
                <g key={route.id}>
                  {isSelected ? (
                    <>
                      {/* Soft ambient orange glow under active route */}
                      <path
                        d={route.d}
                        fill="none"
                        stroke={COLORS.routeGlow}
                        strokeWidth="8"
                        strokeLinecap="round"
                        style={{
                          opacity: inView ? 1 : 0,
                          transition: reducedMotion ? 'none' : 'opacity 0.5s ease 0.2s',
                        }}
                      />
                      {/* Primary strong Zentro orange line */}
                      <path
                        d={route.d}
                        fill="none"
                        stroke={COLORS.orangeStrong}
                        strokeWidth="3.6"
                        strokeLinecap="round"
                        filter="url(#sl-route-glow)"
                        pathLength={100}
                        strokeDasharray="100"
                        strokeDashoffset={inView ? 0 : 100}
                        style={{
                          transition: reducedMotion
                            ? 'none'
                            : `stroke-dashoffset 0.9s cubic-bezier(0.22, 1, 0.36, 1) ${0.15 + idx * 0.08}s`,
                        }}
                      />
                    </>
                  ) : (
                    /* Secondary dashed orange routes */
                    <path
                      d={route.d}
                      fill="none"
                      stroke={COLORS.routeSecondary}
                      strokeWidth="2.2"
                      strokeLinecap="round"
                      strokeDasharray="5 6"
                      style={{
                        opacity: inView ? 0.92 : 0,
                        transition: reducedMotion
                          ? 'none'
                          : `opacity 0.65s ease ${0.25 + idx * 0.1}s`,
                      }}
                    />
                  )}
                </g>
              );
            })}
          </g>

          {/* Subtle Moving Orange Pulse along the active SVG route path */}
          {inView && (
            <g transform={`translate(${vanPosition.x}, ${vanPosition.y})`} aria-hidden="true" style={{ pointerEvents: 'none' }}>
              <circle r="12" fill={COLORS.hubPulse} />
              <circle
                r="8"
                fill={COLORS.orangeStrong}
                stroke={COLORS.white}
                strokeWidth="2.2"
                filter="url(#sl-pill-shadow)"
              />
              <circle r="2.5" fill={COLORS.white} />
            </g>
          )}

          {/* Geographically Placed City Hub Markers & White Location Pills */}
          <g className="sl-hubs-layer">
            {CITY_HUBS.map((city, idx) => {
              const isEndpointOfActive =
                activeRoute.from === city.id || activeRoute.to === city.id;
              const isHovered = hoveredHubId === city.id;
              const pillWidth = city.name.length * 7.5 + 24;
              const pillHeight = 25;
              const offsetX = city.labelPlacement === 'left' ? -(pillWidth + 12) : 12;
              const offsetY = -pillHeight / 2;

              return (
                <g
                  key={city.id}
                  transform={`translate(${city.pt.x}, ${city.pt.y})`}
                  onMouseEnter={() => setHoveredHubId(city.id)}
                  onMouseLeave={() => setHoveredHubId(null)}
                  onClick={() => {
                    const matching = routes.find(
                      (r) => r.from === city.id || r.to === city.id
                    );
                    if (matching) setActiveRouteId(matching.id);
                    const matchingDistrict = SRI_LANKA_DISTRICTS.find((d) => d.id === city.districtId);
                    if (matchingDistrict) setSelectedDistrict(matchingDistrict);
                  }}
                  style={{
                    cursor: 'pointer',
                    opacity: inView ? 1 : 0,
                    transition: reducedMotion
                      ? 'none'
                      : `opacity 0.55s ease ${0.18 + idx * 0.08}s`,
                  }}
                >
                  <g
                    style={{
                      transform: isHovered && !reducedMotion ? 'scale(1.07)' : 'scale(1)',
                      transition: reducedMotion ? 'none' : 'transform 0.22s ease',
                    }}
                  >
                    {/* Soft pulsing aura on active route origin & destination hubs */}
                    {isEndpointOfActive && (
                      <circle
                        r={city.isPrimary ? '16' : '13.5'}
                        fill={COLORS.hubPulse}
                        className={reducedMotion ? undefined : 'pulse-hub'}
                      />
                    )}

                    {/* Outer marker ring */}
                    <circle
                      r={isEndpointOfActive ? '7' : '5.5'}
                      fill={COLORS.white}
                      stroke={isEndpointOfActive ? COLORS.orangeStrong : COLORS.orange}
                      strokeWidth={isEndpointOfActive ? '2.5' : '2'}
                    />

                    {/* Inner Zentro orange pin core */}
                    <circle
                      r={isEndpointOfActive ? '3.5' : '2.5'}
                      fill={isEndpointOfActive ? COLORS.orangeStrong : COLORS.orange}
                    />

                    {/* White location label pill with dark ink typography & orange border */}
                    <g
                      transform={`translate(${offsetX}, ${offsetY})`}
                      filter="url(#sl-pill-shadow)"
                    >
                      <rect
                        width={pillWidth}
                        height={pillHeight}
                        rx="12.5"
                        fill={COLORS.white}
                        stroke={
                          isEndpointOfActive || isHovered
                            ? COLORS.orange
                            : COLORS.borderOrange
                        }
                        strokeWidth={isEndpointOfActive || isHovered ? '1.4' : '1.1'}
                      />
                      <circle
                        cx="10.5"
                        cy={pillHeight / 2}
                        r="2.8"
                        fill={isEndpointOfActive ? COLORS.orangeStrong : COLORS.orange}
                      />
                      <text
                        x="18"
                        y={pillHeight / 2 + 3.8}
                        fill={COLORS.headingText}
                        fontSize="11.2"
                        fontWeight={isEndpointOfActive ? '700' : '600'}
                        fontFamily="Inter, system-ui, -apple-system, sans-serif"
                      >
                        {city.name}
                      </text>
                    </g>
                  </g>
                </g>
              );
            })}
          </g>
        </svg>

        {/* Floating Tooltip Following Hovered District */}
        {hoveredDistrict && (
          <div
            className="sl-district-tooltip"
            style={{
              position: 'absolute',
              left: `${tooltipPos.x}px`,
              top: `${tooltipPos.y}px`,
              pointerEvents: 'none',
              zIndex: 30,
              background: COLORS.tooltipBg,
              borderColor: COLORS.borderOrange,
              borderWidth: '1px',
              borderStyle: 'solid',
              borderRadius: '12px',
              padding: '7px 12px',
              boxShadow: `0 10px 24px ${COLORS.cardShadow}`,
              backdropFilter: 'blur(10px)',
              WebkitBackdropFilter: 'blur(10px)',
              transform: 'translate(-50%, -100%) translateY(-10px)',
              transition: reducedMotion ? 'none' : 'opacity 0.15s ease, transform 0.15s ease',
              whiteSpace: 'nowrap',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
              <span
                style={{
                  width: '6px',
                  height: '6px',
                  borderRadius: '50%',
                  background: COLORS.orangeStrong,
                }}
              />
              <strong style={{ fontSize: '13px', fontWeight: 700, color: COLORS.headingText }}>
                {hoveredDistrict.name}
              </strong>
            </div>
            <div style={{ fontSize: '11px', color: COLORS.bodyText, marginTop: '2px', fontWeight: 500 }}>
              {hoveredDistrict.province}
            </div>
          </div>
        )}

        {/* Selected District Information Card */}
        {selectedDistrict && (
          <div
            className="sl-selected-district-card"
            style={{
              position: 'absolute',
              left: '10px',
              bottom: '10px',
              maxWidth: '220px',
              padding: '12px 14px',
              borderRadius: '16px',
              border: `1px solid ${COLORS.borderOrange}`,
              background: COLORS.statusBg,
              boxShadow: `0 12px 36px ${COLORS.cardShadow}`,
              backdropFilter: 'blur(12px)',
              WebkitBackdropFilter: 'blur(12px)',
              zIndex: 25,
              opacity: inView ? 1 : 0,
              transform: inView ? 'translateY(0)' : 'translateY(12px)',
              transition: reducedMotion ? 'none' : 'opacity 0.4s ease, transform 0.4s ease',
            }}
          >
            <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: '5px' }}>
              <div style={{ display: 'flex', alignItems: 'center', gap: '6px' }}>
                <span
                  style={{
                    width: '24px',
                    height: '24px',
                    borderRadius: '8px',
                    display: 'grid',
                    placeItems: 'center',
                    background: COLORS.orangeSoft,
                    color: COLORS.orangeStrong,
                  }}
                >
                  <MapPin size={13} strokeWidth={2.4} />
                </span>
                <span style={{ fontSize: '10px', fontWeight: 700, letterSpacing: '0.04em', textTransform: 'uppercase', color: COLORS.mutedText }}>
                  Selected District
                </span>
              </div>
              <button
                type="button"
                onClick={() => setSelectedDistrict(null)}
                aria-label="Clear selected district"
                style={{
                  background: 'transparent',
                  border: 'none',
                  cursor: 'pointer',
                  padding: '3px',
                  color: COLORS.mutedText,
                  borderRadius: '6px',
                  display: 'flex',
                  alignItems: 'center',
                  justifyContent: 'center',
                }}
              >
                <X size={13} />
              </button>
            </div>
            <strong style={{ display: 'block', fontSize: '14px', fontWeight: 700, color: COLORS.headingText }}>
              {selectedDistrict.name}
            </strong>
            <p style={{ margin: '2px 0 0', fontSize: '11px', color: COLORS.bodyText, fontWeight: 500 }}>
              {selectedDistrict.province} · Sri Lanka
            </p>
          </div>
        )}

        {/* Lower-Right Floating Delivery Network Card */}
        <div
          className="sl-status-card"
          style={{
            background: COLORS.statusBg,
            borderColor: COLORS.borderOrange,
            opacity: inView ? 1 : 0,
            transform: inView ? 'translateY(0)' : 'translateY(14px)',
            transition: reducedMotion
              ? 'none'
              : 'opacity 0.6s ease 0.4s, transform 0.6s cubic-bezier(0.22, 1, 0.36, 1) 0.4s, box-shadow 0.25s ease',
          }}
        >
          <div className="sl-status-header">
            <span
              className="sl-status-icon"
              style={{ background: COLORS.orangeSoft, color: COLORS.orangeStrong }}
            >
              <Truck size={16} strokeWidth={2.2} />
            </span>
            <strong style={{ color: COLORS.headingText }}>Delivery Network</strong>
          </div>
          <p style={{ color: COLORS.bodyText }}>
            Connected hubs: Colombo, Kandy, Jaffna, Galle, Trincomalee
          </p>
        </div>
      </div>
    </div>
  );
}

export default SriLankaDeliveryMap;
