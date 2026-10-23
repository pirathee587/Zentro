import { describe, expect, it } from 'vitest';
import { mapSupportedRoles, routeForRoles, type Role } from './roles';

describe('role mapping', () => {
  it.each(['CUSTOMER', 'MERCHANT', 'COURIER', 'DISPATCHER'] as Role[])('recognizes %s', (role) => { expect(mapSupportedRoles([role])).toEqual([role]); });
  it('ignores unknown and ADMIN roles', () => { expect(mapSupportedRoles(['ADMIN', 'UNKNOWN', 'CUSTOMER'])).toEqual(['CUSTOMER']); });
  it('does not accept SERVICE or unknown roles as portal access', () => { expect(mapSupportedRoles(['SERVICE', 'ADMIN', 'UNKNOWN'])).toEqual([]); });
  it.each([
    [['CUSTOMER'], '/customer'],
    [['MERCHANT'], '/merchant/dashboard'],
    [['COURIER'], '/courier'],
    [['DISPATCHER'], '/admin'],
    [['CUSTOMER', 'MERCHANT'], '/merchant/dashboard'],
    [['CUSTOMER', 'COURIER', 'DISPATCHER'], '/admin'],
    [[], null],
  ] as [Role[], string | null][])('routes role combination %j to %s', (roles, route) => { expect(routeForRoles(roles)).toBe(route); });
});
