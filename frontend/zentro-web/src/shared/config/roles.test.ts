import { describe, expect, it } from 'vitest';
import { mapSupportedRoles, type Role } from './roles';

describe('role mapping', () => {
  it.each(['CUSTOMER', 'MERCHANT', 'COURIER', 'DISPATCHER', 'SERVICE'] as Role[])('recognizes %s', (role) => { expect(mapSupportedRoles([role])).toEqual([role]); });
  it('ignores unknown and ADMIN roles', () => { expect(mapSupportedRoles(['ADMIN', 'UNKNOWN', 'CUSTOMER'])).toEqual(['CUSTOMER']); });
});
