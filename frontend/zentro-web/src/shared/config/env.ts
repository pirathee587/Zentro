import { z } from 'zod';
const schema=z.object({VITE_API_BASE_URL:z.string().url(),VITE_KEYCLOAK_URL:z.string().url(),VITE_KEYCLOAK_REALM:z.string().min(1),VITE_KEYCLOAK_CLIENT_ID:z.string().min(1)});
export type Environment=z.infer<typeof schema>;
export function parseEnvironment(values:Record<string,string|undefined>):Environment { return schema.parse(values); }
export const env=parseEnvironment(import.meta.env);
