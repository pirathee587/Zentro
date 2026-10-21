export const roles=['CUSTOMER','MERCHANT','COURIER','DISPATCHER','ADMIN'] as const; export type Role=typeof roles[number];
