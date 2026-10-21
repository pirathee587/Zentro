import axios from 'axios'; import { env } from '@/shared/config/env';
let token: string | undefined;
export const setAccessToken=(value:string|undefined) => { token=value; };
export const apiClient=axios.create({baseURL:env.VITE_API_BASE_URL});
apiClient.interceptors.request.use((config) => { if(token) config.headers.Authorization=`Bearer ${token}`; return config; });
