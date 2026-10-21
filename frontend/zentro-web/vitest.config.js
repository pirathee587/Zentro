import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';
import { fileURLToPath, URL } from 'node:url';
export default defineConfig({ plugins: [react()], resolve: { alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) } }, test: { environment: 'jsdom', setupFiles: ['./src/shared/testing/setup.ts'], env: { VITE_API_BASE_URL: 'http://localhost:8080', VITE_KEYCLOAK_URL: 'http://localhost:8180', VITE_KEYCLOAK_REALM: 'zentro', VITE_KEYCLOAK_CLIENT_ID: 'zentro-web' } } });
