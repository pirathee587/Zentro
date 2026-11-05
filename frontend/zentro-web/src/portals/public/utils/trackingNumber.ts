const trackingNumberPattern = /^ZTR-[ABCDEFGHJKLMNPQRSTUVWXYZ23456789]{10}$/;

export function normalizePublicTrackingNumber(value: string): string | undefined {
  const normalized = value.trim().toUpperCase();
  return trackingNumberPattern.test(normalized) ? normalized : undefined;
}
