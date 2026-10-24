const setRegistrationSignInTarget = () => {
  const link = document.querySelector('[data-zentro-signin-return]');
  if (!link) return;

  const match = document.cookie.match(/(?:^|;\s*)zentro_entry_point=([^;]+)/);
  const entryPoint = match ? decodeURIComponent(match[1]) : 'customer';
  link.href = entryPoint === 'merchant'
    ? 'http://localhost:5173/merchant/login'
    : 'http://localhost:5173/login';

  if (match) document.cookie = 'zentro_entry_point=; Max-Age=0; Path=/; SameSite=Lax';
};

// Keycloak may include theme scripts in the document head, before the form link
// exists. Wait until the registration page has been parsed before updating it.
if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', setRegistrationSignInTarget, { once: true });
} else {
  setRegistrationSignInTarget();
}
