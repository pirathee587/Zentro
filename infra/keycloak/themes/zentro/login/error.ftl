<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false; section>
  <#if section = "header">
    <span class="zentro-login-brand"><span class="zentro-login-mark">z</span>zentro</span>
    <span class="zentro-login-title">We couldn't complete that</span>
  <#elseif section = "form">
    <div class="zentro-message" role="alert" aria-live="polite">
      <p><#if message?? && message.summary??>${kcSanitize(message.summary)?no_esc}<#else>Something went wrong while signing you in. Please try again.</#if></p>
      <a class="zentro-message-action" href="${url.loginUrl}">Back to sign in</a>
    </div>
  </#if>
</@layout.registrationLayout>
