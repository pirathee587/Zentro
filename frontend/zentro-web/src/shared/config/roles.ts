export const roles=['CUSTOMER','MERCHANT','COURIER','DISPATCHER','SERVICE'] as const; export type Role=typeof roles[number];
export function mapSupportedRoles(...sources:string[][]):Role[]{const granted=new Set(sources.flat());return roles.filter((role)=>granted.has(role));}
