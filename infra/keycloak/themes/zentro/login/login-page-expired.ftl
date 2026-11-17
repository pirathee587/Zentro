<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false; section>
  <#if section = "header">
    <span class="zentro-login-brand"><span class="zentro-login-mark">z</span>zentro</span>
    <span class="zentro-login-title">Sign-in page expired</span>
    <span class="zentro-login-subtitle">For your security, sign in sessions expire after a short period of inactivity.</span>
  <#elseif section = "form">
    <div class="zentro-message" role="alert" aria-live="polite">
      <p>Your previous sign-in session has expired. Click below to start fresh.</p>
      <a class="zentro-message-action" href="${url.loginRestartFlowUrl}">Restart sign in</a>
    </div>
  </#if>
</@layout.registrationLayout>
