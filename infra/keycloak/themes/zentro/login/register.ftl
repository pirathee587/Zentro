<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false displayRequiredFields=false; section>
  <#if section = "header">
    <span class="zentro-login-brand"><span class="zentro-login-mark">z</span>zentro</span>
    <span class="zentro-login-title">Create Zentro Account</span>
    <span class="zentro-login-subtitle">Email code verification is required to activate your account.</span>
  <#elseif section = "form">
    <div class="zentro-message" role="status" style="text-align: center; padding: 20px 0;">
      <p style="margin-bottom: 24px; color: #63584e; font-size: 15px; line-height: 1.6;">
        We verify accounts with a secure <strong>6-digit code</strong> sent directly to your email. Click below to continue to the registration portal:
      </p>
      <a id="zentro-direct-register-btn" class="zentro-message-action" href="http://localhost/register" style="display: inline-block; background: #231d19; color: #ffffff; padding: 14px 28px; border-radius: 999px; text-decoration: none; font-weight: bold;">
        Continue to Registration
      </a>
      <div style="margin-top: 24px;">
        <a data-zentro-signin-return href="${url.loginUrl}" style="color: #ad7f46; font-size: 14px; text-decoration: none;">
          Already have an account? Sign in
        </a>
      </div>
    </div>
    <script>
      (function() {
        var matchOrigin = document.cookie.match(/(?:^|;\s*)zentro_app_origin=([^;]+)/);
        var origin = matchOrigin ? decodeURIComponent(matchOrigin[1]) : (window.location.protocol + '//' + window.location.hostname);
        var matchEntry = document.cookie.match(/(?:^|;\s*)zentro_entry_point=([^;]+)/);
        var entry = matchEntry ? decodeURIComponent(matchEntry[1]) : 'customer';
        var target = entry === 'merchant' ? (origin + '/register?next=merchant') : (origin + '/register');
        var btn = document.getElementById('zentro-direct-register-btn');
        if (btn) btn.href = target;
        window.location.replace(target);
      })();
    </script>
  </#if>
</@layout.registrationLayout>
