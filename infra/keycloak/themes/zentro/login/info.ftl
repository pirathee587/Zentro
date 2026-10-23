<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=false; section>
  <#if section = "header">
    <span class="zentro-login-brand"><span class="zentro-login-mark">z</span>zentro</span>
    <span class="zentro-login-title">A quick update</span>
  <#elseif section = "form">
    <div class="zentro-message" role="status" aria-live="polite">
      <p><#if message?? && message.summary??>${kcSanitize(message.summary)?no_esc}<#else>Your request has been received.</#if></p>
      <#if pageRedirectUri??><a class="zentro-message-action" href="${pageRedirectUri}">Continue</a><#else><a class="zentro-message-action" href="${url.loginUrl}">Back to sign in</a></#if>
    </div>
  </#if>
</@layout.registrationLayout>
