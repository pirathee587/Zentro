<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "buttons.ftl" as buttons>
<#import "passkeys.ftl" as passkeys>
<#-- Keycloak shows its standard "reauthenticate" message when prompt=login meets an existing
     SSO session. Render the normal form for it: auto-restarting the flow here re-enters the
     same prompt=login request and loops forever. Expired actions use login-page-expired.ftl. -->
<@layout.registrationLayout displayMessage=true displayInfo=realm.password && realm.registrationAllowed && !registrationDisabled??; section>
  <#if section = "header">
    <span class="zentro-login-brand"><span class="zentro-login-mark">z</span>zentro</span>
    <span class="zentro-login-title">${msg("loginAccountTitle")}</span>
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
            {/* Gradients */}
            <linearGradient id="sideVanBodyGrad" x1="0%" y1="0%" x2="0%" y2="100%">
              <stop offset="0%" stop-color="#ffffff" />
              <stop offset="45%" stop-color="#fcfaf6" />
              <stop offset="85%" stop-color="#f0e9dd" />
              <stop offset="100%" stop-color="#e2d8c7" />
            </linearGradient>

            <linearGradient id="sideVanCabinGrad" x1="0%" y1="0%" x2="100%" y2="100%">
              <stop offset="0%" stop-color="#f7ba52" />
              <stop offset="45%" stop-color="#ea9b2c" />
              <stop offset="100%" stop-color="#c57712" />
            </linearGradient>

            <linearGradient id="sideGoldStripe" x1="0%" y1="0%" x2="100%" y2="0%">
              <stop offset="0%" stop-color="#f59e0b" />
              <stop offset="50%" stop-color="#fbbf24" />
              <stop offset="100%" stop-color="#d97706" />
            </linearGradient>

            <linearGradient id="sideWindshieldGrad" x1="0%" y1="0%" x2="0%" y2="100%">
              <stop offset="0%" stop-color="#38bdf8" stop-opacity="0.85" />
              <stop offset="35%" stop-color="#1e293b" stop-opacity="0.95" />
              <stop offset="100%" stop-color="#0f172a" stop-opacity="0.98" />
            </linearGradient>

            <linearGradient id="sideHeadlightBeam" x1="0%" y1="50%" x2="100%" y2="50%">
              <stop offset="0%" stop-color="#fffbeb" stop-opacity="0.75" />
              <stop offset="40%" stop-color="#fef08a" stop-opacity="0.25" />
              <stop offset="100%" stop-color="#fef08a" stop-opacity="0" />
            </linearGradient>

            <filter id="sideTruckShadow" x="-10%" y="-10%" width="125%" height="135%">
              <feDropShadow dx="0" dy="10" stdDeviation="10" flood-color="#000000" flood-opacity="0.12" />
            </filter>
          </defs>

          {/* Headlight beam */}
          <path d="M395 148 L510 120 L515 195 L400 168 Z" fill="url(#sideHeadlightBeam)" />

          {/* Ground shadow */}
          <ellipse cx="250" cy="202" rx="180" ry="8" fill="#090705" opacity="0.12" />

          {/* Road asphalt and speed dashes */}
          <line x1="20" y1="200" x2="500" y2="200" stroke="#ded3c2" stroke-width="3" stroke-linecap="round" />
          <line x1="20" y1="206" x2="500" y2="206" stroke="#e5b574" stroke-width="2.5" stroke-dasharray="14 18" stroke-linecap="round" class="kc-road-dash" />

          {/* Speed drift wind lines */}
          <line x1="80" y1="95" x2="35" y2="95" stroke="#c98f4a" stroke-width="2" stroke-dasharray="8 12" opacity="0.65" />
          <line x1="95" y1="115" x2="25" y2="115" stroke="#e5b574" stroke-width="1.8" stroke-dasharray="12 16" opacity="0.75" />
          <line x1="105" y1="135" x2="45" y2="135" stroke="#c98f4a" stroke-width="2.2" stroke-dasharray="16 14" opacity="0.6" />

          {/* Animated bouncing truck */}
          <g class="kc-truck-bounce" filter="url(#sideTruckShadow)">
            {/* Cargo Box */}
            <rect x="100" y="65" width="185" height="110" rx="10" fill="url(#sideVanBodyGrad)" stroke="#cfc4b2" stroke-width="1.8" />

            {/* Cabin */}
            <path d="M283 80 H332 C348 80 360 92 370 108 L390 137 C396 146 400 152 400 162 V175 H283 Z" fill="url(#sideVanCabinGrad)" stroke="#b86f10" stroke-width="1.8" />

            {/* Windshield */}
            <path d="M294 88 H328 C338 88 350 98 358 110 L372 134 H294 Z" fill="url(#sideWindshieldGrad)" stroke="#1e293b" stroke-width="1.2" />
            <path d="M326 90 L314 130 H306 L318 90 Z" fill="#ffffff" opacity="0.3" />

            {/* Driver */}
            <circle cx="322" cy="112" r="8" fill="#0f172a" opacity="0.9" />
            <path d="M310 132 C310 122 316 119 326 119 C334 119 340 122 342 132 Z" fill="#0f172a" opacity="0.9" />

            {/* Side Mirror */}
            <rect x="362" y="122" width="7" height="13" rx="2.5" fill="#ea9b2c" stroke="#b86f10" stroke-width="1" />

            {/* Gold Streamline stripes */}
            <rect x="100" y="128" width="183" height="9" fill="url(#sideGoldStripe)" />
            <rect x="100" y="140" width="150" height="3.5" fill="#fbbf24" opacity="0.9" />

            {/* Zentro Express Branding */}
            <g transform="translate(130, 80)">
              <circle cx="18" cy="18" r="16" fill="#150f0a" />
              <text x="18" y="24" font-family="Georgia, serif" font-size="18" font-weight="bold" fill="#e5b574" text-anchor="middle">z</text>
              <text x="42" y="18" font-family="'Plus Jakarta Sans', sans-serif" font-size="15" font-weight="800" fill="#150f0a" letter-spacing="-0.4">zentro</text>
              <text x="43" y="28" font-family="'Plus Jakarta Sans', sans-serif" font-size="7" font-weight="800" fill="#c98f4a" letter-spacing="1.4">EXPRESS</text>
            </g>

            {/* Front Bumper & Headlight */}
            <path d="M390 167 H404 C406 167 408 169 408 171 V180 C408 181 406 183 404 183 H376 L384 167 Z" fill="#1e1814" stroke="#a15e09" stroke-width="1.2" />
            <rect x="392" y="146" width="10" height="12" rx="3" fill="#fffbeb" />
            <circle cx="397" cy="152" r="3.5" fill="#facc15" />

            {/* Taillight */}
            <rect x="97" y="148" width="4" height="13" rx="1.5" fill="#e53935" />

            {/* Wheel Arch Cutouts */}
            <path d="M 129 175 A 30 30 0 0 1 189 175 Z" fill="#0d0906" />
            <path d="M 319 175 A 30 30 0 0 1 379 175 Z" fill="#0d0906" />

            {/* Rear Wheel (cx = 159, cy = 175, radius = 24, touches road at y = 199) */}
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

            {/* Front Wheel (cx = 349, cy = 175, radius = 24, touches road at y = 199) */}
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
      <#if realm.password>
        <form id="kc-form-login" class="${properties.kcFormClass!}" onsubmit="login.disabled = true; return true;" action="${url.loginAction}" method="post" novalidate="novalidate">
          <#if login.username?? && login.username?has_content>
            <div class="zentro-email-chip" id="zentro-email-chip">
              <span id="zentro-chip-text">${login.username}</span>
              <button type="button" id="zentro-change-btn">Change</button>
            </div>
            <div id="zentro-username-wrapper" style="display: none; margin-bottom: 16px;">
              <#assign label><#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if></#assign>
              <@field.input name="username" label=label error=messagesPerField.getFirstError('username','password') autofocus=false autocomplete="username" value=login.username />
              <div style="text-align: right; margin-top: 6px;">
                <a id="zentro-back-app-link" href="#" style="font-size: 12px; color: var(--bronze); text-decoration: none; font-weight: 600;">← Change from Zentro sign in</a>
              </div>
            </div>
            <@field.password name="password" label=msg("password") error=messagesPerField.getFirstError('password','username') forgotPassword=realm.resetPasswordAllowed autofocus=true autocomplete="current-password">
              <#if realm.rememberMe><@field.checkbox name="rememberMe" label=msg("rememberMe") value=login.rememberMe?? /></#if>
            </@field.password>
          <#elseif usernameHidden??>
            <@field.password name="password" label=msg("password") error=messagesPerField.getFirstError('password','username') forgotPassword=realm.resetPasswordAllowed autofocus=true autocomplete="current-password" />
          <#else>
            <#assign label><#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if></#assign>
            <@field.input name="username" label=label error=messagesPerField.getFirstError('username','password') autofocus=true autocomplete="username" value=login.username!'' />
            <@field.password name="password" label=msg("password") error=messagesPerField.getFirstError('password') forgotPassword=realm.resetPasswordAllowed autofocus=false autocomplete="current-password">
              <#if realm.rememberMe><@field.checkbox name="rememberMe" label=msg("rememberMe") value=login.rememberMe?? /></#if>
            </@field.password>
          </#if>
          <input type="hidden" id="id-hidden-input" name="credentialId" <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if>/>
          <@buttons.loginButton />
        </form>
      </#if>
    </div></div>
    <script>
      (function() {
        var changeBtn = document.getElementById('zentro-change-btn');
        var chip = document.getElementById('zentro-email-chip');
        var wrapper = document.getElementById('zentro-username-wrapper');
        var input = document.getElementById('username');
        if (changeBtn && chip && wrapper) {
          changeBtn.addEventListener('click', function(e) {
            e.preventDefault();
            chip.style.display = 'none';
            wrapper.style.display = 'block';
            if (input) {
              input.focus();
              input.select();
            }
          });
        }
        var backLink = document.getElementById('zentro-back-app-link');
        if (backLink) {
          var match = document.cookie.match(/(?:^|;\s*)zentro_app_origin=([^;]+)/);
          var entry = document.cookie.match(/(?:^|;\s*)zentro_entry_point=([^;]+)/);
          var origin = match ? decodeURIComponent(match[1]) : window.location.origin;
          var path = entry && decodeURIComponent(entry[1]) === 'staff' ? '/staff/login'
                   : entry && decodeURIComponent(entry[1]) === 'merchant' ? '/merchant/login'
                   : '/login';
          backLink.href = origin + path;
        }
      })();
    </script>
    <@passkeys.conditionalUIData />
  <#elseif section = "socialProviders">
    <#if realm.password && social.providers?? && social.providers?has_content>
      <div id="kc-social-providers" class="zentro-social-providers">
        <div class="zentro-social-divider"><span>or continue with</span></div>
        <#list social.providers as p>
          <a id="social-${p.alias}" class="zentro-social-button" href="${p.loginUrl}">
            <span class="zentro-social-icon" aria-hidden="true"><#if p.alias == "google">G<#else>${p.alias?substring(0, 1)?upper_case}</#if></span>
            <span>Continue with ${p.displayName!}</span>
          </a>
        </#list>
      </div>
    </#if>
  </#if>
</@layout.registrationLayout>
