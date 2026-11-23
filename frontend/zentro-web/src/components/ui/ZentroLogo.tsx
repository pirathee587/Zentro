import type { ImgHTMLAttributes } from 'react';

/**
 * Official Zentro Speeding Parcel Brand Logo:
 * Renders the authentic high-resolution speeding parcel artwork
 * with its natural aspect ratio, speed trails, and signature ribbon notch.
 */
export function ZentroLogo({ className = '', alt = 'Zentro', ...props }: ImgHTMLAttributes<HTMLImageElement>) {
  return (
    <img
      src="/images/zentro-logo.png"
      alt={alt}
      className={`object-contain shrink-0 ${className}`.trim()}
      loading="eager"
      decoding="async"
      {...props}
    />
  );
}

export default ZentroLogo;
