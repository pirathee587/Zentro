<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false; section>
  <#if section = "header">
    <span class="zentro-login-brand"><span class="zentro-login-mark">z</span>zentro</span>
    <span class="zentro-login-title">Sign in to your account</span>
    <span class="zentro-login-subtitle">Sign in to manage your deliveries and account.</span>
  <#elseif section = "form">
    <aside id="zentro-auth-side-art" class="zentro-auth-side-panel" aria-label="Zentro Express Delivery">
      <div class="zentro-side-brand">
        <span class="zentro-side-mark">z</span>
        <span class="zentro-side-name">zentro</span>
      </div>

      <div class="zentro-side-truck-stage">
        <svg class="zentro-side-truck-svg" viewBox="0 0 520 240" fill="none" xmlns="http://www.w3.org/2000/svg">
          <defs>
            <linearGradient id="sideVanBodyGradErr" x1="0%" y1="0%" x2="0%" y2="100%">
              <stop offset="0%" stop-color="#ffffff" />
              <stop offset="45%" stop-color="#fcfaf6" />
              <stop offset="85%" stop-color="#f0e9dd" />
              <stop offset="100%" stop-color="#e2d8c7" />
            </linearGradient>

            <linearGradient id="sideVanCabinGradErr" x1="0%" y1="0%" x2="100%" y2="100%">
              <stop offset="0%" stop-color="#f7ba52" />
              <stop offset="45%" stop-color="#ea9b2c" />
              <stop offset="100%" stop-color="#c57712" />
            </linearGradient>

            <linearGradient id="sideGoldStripeErr" x1="0%" y1="0%" x2="100%" y2="0%">
              <stop offset="0%" stop-color="#f59e0b" />
              <stop offset="50%" stop-color="#fbbf24" />
              <stop offset="100%" stop-color="#d97706" />
            </linearGradient>

            <linearGradient id="sideWindshieldGradErr" x1="0%" y1="0%" x2="0%" y2="100%">
              <stop offset="0%" stop-color="#38bdf8" stop-opacity="0.85" />
              <stop offset="35%" stop-color="#1e293b" stop-opacity="0.95" />
              <stop offset="100%" stop-color="#0f172a" stop-opacity="0.98" />
            </linearGradient>

            <linearGradient id="sideHeadlightBeamErr" x1="0%" y1="50%" x2="100%" y2="50%">
              <stop offset="0%" stop-color="#fffbeb" stop-opacity="0.75" />
              <stop offset="40%" stop-color="#fef08a" stop-opacity="0.25" />
              <stop offset="100%" stop-color="#fef08a" stop-opacity="0" />
            </linearGradient>

            <filter id="sideTruckShadowErr" x="-10%" y="-10%" width="125%" height="135%">
              <feDropShadow dx="0" dy="10" stdDeviation="10" flood-color="#000000" flood-opacity="0.12" />
            </filter>
          </defs>

          <path d="M395 148 L510 120 L515 195 L400 168 Z" fill="url(#sideHeadlightBeamErr)" />
          <ellipse cx="250" cy="202" rx="180" ry="8" fill="#090705" opacity="0.12" />
          <line x1="20" y1="200" x2="500" y2="200" stroke="#ded3c2" stroke-width="3" stroke-linecap="round" />
          <line x1="20" y1="206" x2="500" y2="206" stroke="#e5b574" stroke-width="2.5" stroke-dasharray="14 18" stroke-linecap="round" class="kc-road-dash" />
          <line x1="80" y1="95" x2="35" y2="95" stroke="#c98f4a" stroke-width="2" stroke-dasharray="8 12" opacity="0.65" />
          <line x1="95" y1="115" x2="25" y2="115" stroke="#e5b574" stroke-width="1.8" stroke-dasharray="12 16" opacity="0.75" />
          <line x1="105" y1="135" x2="45" y2="135" stroke="#c98f4a" stroke-width="2.2" stroke-dasharray="16 14" opacity="0.6" />

          <g class="kc-truck-bounce" filter="url(#sideTruckShadowErr)">
            <rect x="100" y="65" width="185" height="110" rx="10" fill="url(#sideVanBodyGradErr)" stroke="#cfc4b2" stroke-width="1.8" />
            <path d="M283 80 H332 C348 80 360 92 370 108 L390 137 C396 146 400 152 400 162 V175 H283 Z" fill="url(#sideVanCabinGradErr)" stroke="#b86f10" stroke-width="1.8" />
            <path d="M294 88 H328 C338 88 350 98 358 110 L372 134 H294 Z" fill="url(#sideWindshieldGradErr)" stroke="#1e293b" stroke-width="1.2" />
            <path d="M326 90 L314 130 H306 L318 90 Z" fill="#ffffff" opacity="0.3" />

            <circle cx="322" cy="112" r="8" fill="#0f172a" opacity="0.9" />
            <path d="M310 132 C310 122 316 119 326 119 C334 119 340 122 342 132 Z" fill="#0f172a" opacity="0.9" />

            <rect x="362" y="122" width="7" height="13" rx="2.5" fill="#ea9b2c" stroke="#b86f10" stroke-width="1" />
            <rect x="100" y="128" width="183" height="9" fill="url(#sideGoldStripeErr)" />
            <rect x="100" y="140" width="150" height="3.5" fill="#fbbf24" opacity="0.9" />

            <g transform="translate(130, 80)">
              <circle cx="18" cy="18" r="16" fill="#150f0a" />
              <text x="18" y="24" font-family="Georgia, serif" font-size="18" font-weight="bold" fill="#e5b574" text-anchor="middle">z</text>
              <text x="42" y="18" font-family="'Plus Jakarta Sans', sans-serif" font-size="15" font-weight="800" fill="#150f0a" letter-spacing="-0.4">zentro</text>
              <text x="43" y="28" font-family="'Plus Jakarta Sans', sans-serif" font-size="7" font-weight="800" fill="#c98f4a" letter-spacing="1.4">EXPRESS</text>
            </g>

            <path d="M390 167 H404 C406 167 408 169 408 171 V180 C408 181 406 183 404 183 H376 L384 167 Z" fill="#1e1814" stroke="#a15e09" stroke-width="1.2" />
            <rect x="392" y="146" width="10" height="12" rx="3" fill="#fffbeb" />
            <circle cx="397" cy="152" r="3.5" fill="#facc15" />
            <rect x="97" y="148" width="4" height="13" rx="1.5" fill="#e53935" />

            <path d="M 129 175 A 30 30 0 0 1 189 175 Z" fill="#0d0906" />
            <path d="M 319 175 A 30 30 0 0 1 379 175 Z" fill="#0d0906" />

            <g transform="translate(159, 175)">
              <circle cx="0" cy="0" r="24" fill="#15120f" stroke="#090705" stroke-width="2" />
              <circle cx="0" cy="0" r="20" fill="#241e19" />
              <g class="kc-wheel-spin">
                <animateTransform attributeName="transform" type="rotate" from="0 0 0" to="360 0 0" dur="0.8s" repeatCount="indefinite" />
                <circle cx="0" cy="0" r="14" fill="#382b20" stroke="#c98f4a" stroke-width="1.6" />
                <circle cx="0" cy="0" r="6" fill="#e5b574" />
                <line x1="0" y1="-14" x2="0" y2="14" stroke="#e5b574" stroke-width="2.4" />
                <line x1="-12.1" y1="-7" x2="12.1" y2="7" stroke="#e5b574" stroke-width="2.4" />
                <line x1="-12.1" y1="7" x2="12.1" y2="-7" stroke="#e5b574" stroke-width="2.4" />
                <circle cx="0" cy="0" r="3" fill="#15120f" />
              </g>
            </g>

            <g transform="translate(349, 175)">
              <circle cx="0" cy="0" r="24" fill="#15120f" stroke="#090705" stroke-width="2" />
              <circle cx="0" cy="0" r="20" fill="#241e19" />
              <g class="kc-wheel-spin">
                <animateTransform attributeName="transform" type="rotate" from="0 0 0" to="360 0 0" dur="0.8s" repeatCount="indefinite" />
                <circle cx="0" cy="0" r="14" fill="#382b20" stroke="#c98f4a" stroke-width="1.6" />
                <circle cx="0" cy="0" r="6" fill="#e5b574" />
                <line x1="0" y1="-14" x2="0" y2="14" stroke="#e5b574" stroke-width="2.4" />
                <line x1="-12.1" y1="-7" x2="12.1" y2="7" stroke="#e5b574" stroke-width="2.4" />
                <line x1="-12.1" y1="7" x2="12.1" y2="-7" stroke="#e5b574" stroke-width="2.4" />
                <circle cx="0" cy="0" r="3" fill="#15120f" />
              </g>
            </g>
          </g>
        </svg>
      </div>

      <div class="zentro-side-copy">
        <h1 class="zentro-side-title">Thoughtful delivery,<br /><em>made for Sri Lanka.</em></h1>
        <p class="zentro-side-note">A little closer, every time.</p>
      </div>
    </aside>
    <script>
      (function(){
        try {
          var c = document.querySelector('.pf-v5-c-login__container');
          var s = document.getElementById('zentro-auth-side-art');
          if (c && s && s.parentElement !== c) {
            c.insertBefore(s, c.firstChild);
            c.classList.add('has-side-art');
          }
        } catch(e){}
      })();
    </script>

    <div id="kc-form"><div id="kc-form-wrapper">
      <div class="alert alert-error pf-v5-c-alert pf-m-danger" role="alert" aria-live="assertive" style="display: flex; align-items: flex-start; gap: 10px; margin-bottom: 20px; padding: 12px 16px; border-radius: 12px; border: 1px solid #ef4444; background: rgba(239, 68, 68, 0.08); color: #ef4444; font-size: 14px; font-weight: 500; line-height: 1.5;">
        <svg style="flex-shrink: 0; margin-top: 1px;" width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="#ef4444" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round">
          <circle cx="12" cy="12" r="10" />
          <line x1="12" y1="8" x2="12" y2="12" />
          <line x1="12" y1="16" x2="12.01" y2="16" />
        </svg>
        <span><#if message?? && message.summary??>${kcSanitize(message.summary)?no_esc}<#else>Authentication error occurred. Please try again.</#if></span>
      </div>

      <form id="kc-form-login" class="${properties.kcFormClass!}" action="${url.loginUrl}" method="get">
        <div class="pf-v5-c-form__group">
          <label class="pf-v5-c-form__label" for="username">
            <span class="pf-v5-c-form__label-text">${msg("usernameOrEmail")}</span>
          </label>
          <span class="pf-v5-c-form-control">
            <input id="username" class="pf-v5-c-form-control" name="username" type="text" autofocus autocomplete="username" placeholder="you@example.com" required />
          </span>
        </div>
        <div class="pf-v5-c-form__group pf-m-action" style="margin-top: 20px;">
          <button class="pf-v5-c-button pf-m-primary pf-m-block" type="submit" style="width: 100%;">
            Continue securely
          </button>
        </div>
      </form>
    </div></div>
  </#if>
</@layout.registrationLayout>
