export const roles = ['CUSTOMER', 'MERCHANT', 'COURIER', 'DISPATCHER'] as const;
export type Role = (typeof roles)[number];

export function mapSupportedRoles(...sources: string[][]): Role[] {
  const granted = new Set(sources.flat());
  return roles.filter((role) => granted.has(role));
}

export function routeForRoles(userRoles: Role[]): string | null {
  if (userRoles.includes('DISPATCHER')) return '/admin';
  if (userRoles.includes('COURIER')) return '/courier';
  if (userRoles.includes('MERCHANT')) return '/merchant/dashboard';
  if (userRoles.includes('CUSTOMER')) return '/customer';
  return null;
}
