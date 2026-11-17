const setRegistrationSignInTarget = () => {
  const link = document.querySelector('[data-zentro-signin-return]');
  if (!link) return;

  const originMatch = document.cookie.match(/(?:^|;\s*)zentro_app_origin=([^;]+)/);
  let appOrigin = originMatch ? decodeURIComponent(originMatch[1]) : '';

  if (!appOrigin && document.referrer) {
    try {
      const ref = new URL(document.referrer);
      if (ref.hostname === window.location.hostname) {
        appOrigin = ref.origin;
      }
    } catch {
      // ignore
    }
  }

  if (!appOrigin) {
    // Default to port 80 / standard web port on current host
    appOrigin = window.location.protocol + '//' + window.location.hostname;
  }

  const match = document.cookie.match(/(?:^|;\s*)zentro_entry_point=([^;]+)/);
  const entryPoint = match ? decodeURIComponent(match[1]) : 'customer';

  link.href = entryPoint === 'merchant'
    ? `${appOrigin}/merchant/login`
    : `${appOrigin}/login`;

  if (match) document.cookie = 'zentro_entry_point=; Max-Age=0; Path=/; SameSite=Lax';
  if (originMatch) document.cookie = 'zentro_app_origin=; Max-Age=0; Path=/; SameSite=Lax';
};

const mountSideArtPanel = () => {
  try {
    const container = document.querySelector('.pf-v5-c-login__container');
    const sideArt = document.getElementById('zentro-auth-side-art');
    if (container && sideArt && sideArt.parentElement !== container) {
      container.insertBefore(sideArt, container.firstChild);
      container.classList.add('has-side-art');
    }
  } catch {
    // ignore
  }
};

if (document.readyState === 'loading') {
  document.addEventListener('DOMContentLoaded', () => {
    setRegistrationSignInTarget();
    mountSideArtPanel();
  }, { once: true });
} else {
  setRegistrationSignInTarget();
  mountSideArtPanel();
}
