<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false; section>
  <#if section = "header">
    <span class="zentro-login-brand"><span class="zentro-login-mark">z</span>zentro</span>
    <span class="zentro-login-title">Check your inbox</span>
    <span class="zentro-login-subtitle">We have sent an email with an activation link to your email address.</span>
  <#elseif section = "form">
    <div class="zentro-message" role="status" aria-live="polite">
      <p>Please check your email and click the verification link to complete registration.</p>
      <p>Didn't receive the email? <a href="${url.loginAction}">Click here to resend</a>.</p>
      <a class="zentro-message-action" href="${url.loginUrl}">Back to sign in</a>
    </div>
  </#if>
</@layout.registrationLayout>
