import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const envPath = fileURLToPath(new URL('./.env', import.meta.url));
const envText = await readFile(envPath, 'utf8');
const env = Object.fromEntries(envText.split(/\r?\n/).flatMap((line) => {
  const match = line.match(/^\s*([^#=]+)=(.*)$/);
  if (!match) return [];
  return [[match[1].trim(), match[2].trim().replace(/^(["'])(.*)\1$/, '$2')]];
}));

const baseUrl = (env.KEYCLOAK_URL || `http://localhost:${env.KC_HTTP_PORT || '8180'}`).replace(/\/$/, '');
const realmName = env.KEYCLOAK_REALM || 'zentro';
const required = ['GOOGLE_CLIENT_ID', 'GOOGLE_CLIENT_SECRET', 'KC_BOOTSTRAP_ADMIN_USERNAME', 'KC_BOOTSTRAP_ADMIN_PASSWORD'];
const missing = required.filter((name) => !env[name]);
if (missing.length) throw new Error(`Missing required settings in infra/keycloak/.env: ${missing.join(', ')}`);

async function request(path, options = {}) {
  const response = await fetch(`${baseUrl}${path}`, options);
  if (!response.ok) throw new Error(`Keycloak request failed (${response.status}) for ${path}`);
  const body = await response.text();
  return body ? JSON.parse(body) : undefined;
}

const token = await request('/realms/master/protocol/openid-connect/token', {
  method: 'POST',
  headers: { 'content-type': 'application/x-www-form-urlencoded' },
  body: new URLSearchParams({
    grant_type: 'password',
    client_id: 'admin-cli',
    username: env.KC_BOOTSTRAP_ADMIN_USERNAME,
    password: env.KC_BOOTSTRAP_ADMIN_PASSWORD,
  }),
});
const headers = {
  authorization: `Bearer ${token.access_token}`,
  'content-type': 'application/json',
};
const api = `/admin/realms/${encodeURIComponent(realmName)}`;

const firstBrokerExecutions = await request(`${api}/authentication/flows/first%20broker%20login/executions`, { headers });
const reviewProfileExecution = firstBrokerExecutions.find(({ providerId }) => providerId === 'idp-review-profile');
if (!reviewProfileExecution?.authenticationConfig) throw new Error('First broker login Review Profile configuration was not found.');
const reviewConfigPath = `${api}/authentication/config/${encodeURIComponent(reviewProfileExecution.authenticationConfig)}`;
const reviewConfig = await request(reviewConfigPath, { headers });
reviewConfig.config = { ...reviewConfig.config, 'update.profile.on.first.login': 'off' };
await request(reviewConfigPath, { method: 'PUT', headers, body: JSON.stringify(reviewConfig) });
const verifiedReviewConfig = await request(reviewConfigPath, { headers });
if (verifiedReviewConfig.config?.['update.profile.on.first.login'] !== 'off') {
  throw new Error('Keycloak still prompts users to review profile details on first Google sign-in.');
}

const realm = await request(api, { headers });
if (realm.loginTheme !== 'zentro') {
  realm.loginTheme = 'zentro';
  await request(api, { method: 'PUT', headers, body: JSON.stringify(realm) });
}

const googleProvider = {
  alias: 'google',
  displayName: 'Google',
  providerId: 'google',
  enabled: true,
  trustEmail: false,
  storeToken: false,
  addReadTokenRoleOnCreate: false,
  authenticateByDefault: false,
  linkOnly: false,
  firstBrokerLoginFlowAlias: 'first broker login',
  config: {
    clientId: env.GOOGLE_CLIENT_ID,
    clientSecret: env.GOOGLE_CLIENT_SECRET,
    defaultScope: 'openid profile email',
    syncMode: 'IMPORT',
  },
};
const existingProviders = await request(`${api}/identity-provider/instances`, { headers });
const existingGoogle = existingProviders.find((provider) => provider.alias === 'google');
await request(existingGoogle
  ? `${api}/identity-provider/instances/google`
  : `${api}/identity-provider/instances`, {
    method: existingGoogle ? 'PUT' : 'POST',
    headers,
    body: JSON.stringify(googleProvider),
  });

const verified = await request(`${api}/identity-provider/instances/google`, { headers });
if (!verified.enabled || verified.providerId !== 'google') {
  throw new Error('Google identity provider verification failed.');
}

const profileComponentType = 'org.keycloak.userprofile.UserProfileProvider';
const profileComponents = await request(`${api}/components?type=${encodeURIComponent(profileComponentType)}`, { headers });
const profileComponent = profileComponents[0];
if (!profileComponent?.id) throw new Error('Keycloak declarative user-profile component was not found.');
const profileConfig = JSON.parse(profileComponent.config['kc.user.profile.config'][0]);
for (const attribute of profileConfig.attributes) {
  if (['firstName', 'lastName', 'phone'].includes(attribute.name)) delete attribute.required;
}
profileComponent.config['kc.user.profile.config'] = [JSON.stringify(profileConfig)];
await request(`${api}/components/${encodeURIComponent(profileComponent.id)}`, {
  method: 'PUT',
  headers,
  body: JSON.stringify(profileComponent),
});

const realmExportPath = fileURLToPath(new URL('./realm-export.json', import.meta.url));
const realmExport = JSON.parse(await readFile(realmExportPath, 'utf8'));
const exportedProfile = realmExport.components?.[profileComponentType]?.[0];
if (exportedProfile?.config?.['kc.user.profile.config']?.[0]) {
  const exportedProfileConfig = JSON.parse(exportedProfile.config['kc.user.profile.config'][0]);
  for (const attribute of exportedProfileConfig.attributes) {
    if (['firstName', 'lastName', 'phone'].includes(attribute.name)) delete attribute.required;
  }
  exportedProfile.config['kc.user.profile.config'] = [JSON.stringify(exportedProfileConfig)];
  await writeFile(realmExportPath, `${JSON.stringify(realmExport, null, 2)}\n`, 'utf8');
}

const verifiedComponents = await request(`${api}/components?type=${encodeURIComponent(profileComponentType)}`, { headers });
const verifiedProfileConfig = JSON.parse(verifiedComponents[0].config['kc.user.profile.config'][0]);
const stillRequired = verifiedProfileConfig.attributes.filter((attribute) =>
  ['firstName', 'lastName', 'phone'].includes(attribute.name) && attribute.required?.roles?.includes('user'));
if (stillRequired.length) throw new Error('Keycloak still requires profile details during first sign-in.');
const authorizationUrl = new URL(`${baseUrl}/realms/${encodeURIComponent(realmName)}/protocol/openid-connect/auth`);
authorizationUrl.search = new URLSearchParams({
  client_id: 'zentro-web',
  redirect_uri: 'http://localhost:5173/callback',
  response_type: 'code',
  scope: 'openid profile email roles',
  state: 'google-provider-check',
  code_challenge: 'AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA',
  code_challenge_method: 'S256',
  prompt: 'login',
  kc_idp_hint: 'google',
}).toString();
const authorizationResponse = await fetch(authorizationUrl, { redirect: 'manual' });
const authorizationLocation = authorizationResponse.headers.get('location');
if (authorizationResponse.status !== 302 || !authorizationLocation || new URL(authorizationLocation).hostname !== 'accounts.google.com') {
  const destination = authorizationLocation ? new URL(authorizationLocation) : null;
  const handedToGoogleBroker = authorizationResponse.status === 303
    && destination?.hostname === new URL(baseUrl).hostname
    && destination.pathname.endsWith('/broker/google/login');
  if (!handedToGoogleBroker) {
    throw new Error(`Keycloak Google redirect check failed (HTTP ${authorizationResponse.status}${destination ? ` to ${destination.host}${destination.pathname}` : ', no redirect location'}).`);
  }
}
console.log(`Google sign-in is enabled in Keycloak realm '${realmName}'; profile name and phone fields are optional.`);
