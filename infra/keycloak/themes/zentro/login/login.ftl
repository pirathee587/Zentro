<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "buttons.ftl" as buttons>
<#import "passkeys.ftl" as passkeys>
<#-- Keycloak can land on this screen when an earlier login action expired. Restart
     the same authorization flow automatically instead of making the user click
     "Restart login" before reaching the normal sign-in form. -->
<#if message?? && message.summary == msg("reauthenticate")>
<!doctype html>
<html lang="en">
  <head>
    <meta charset="utf-8" />
    <meta http-equiv="refresh" content="0;url=${url.loginRestartFlowUrl}" />
    <title>Restarting sign in</title>
  </head>
  <body>
    <p>Restarting sign in... <a href="${url.loginRestartFlowUrl}">Continue</a></p>
  </body>
</html>
<#else>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('username','password') displayInfo=realm.password && realm.registrationAllowed && !registrationDisabled??; section>
  <#if section = "header">
    <span class="zentro-login-brand"><span class="zentro-login-mark">z</span>zentro</span>
    <span class="zentro-login-title">${msg("loginAccountTitle")}</span>
    <span class="zentro-login-subtitle">Sign in to manage your deliveries and account.</span>
  <#elseif section = "form">
    <div id="kc-form"><div id="kc-form-wrapper">
      <#if realm.password>
        <form id="kc-form-login" class="${properties.kcFormClass!}" onsubmit="login.disabled = true; return true;" action="${url.loginAction}" method="post" novalidate="novalidate">
          <#if login.username?? && login.username?has_content>
            <div class="zentro-email-chip"><span>${login.username}</span><a href="${url.loginUrl}">Change</a></div>
            <input type="hidden" id="username" name="username" value="${login.username}" />
            <@field.password name="password" label=msg("password") error="" forgotPassword=realm.resetPasswordAllowed autofocus=false autocomplete="current-password">
              <#if realm.rememberMe><@field.checkbox name="rememberMe" label=msg("rememberMe") value=login.rememberMe?? /></#if>
            </@field.password>
          <#elseif usernameHidden??>
            <@field.password name="password" label=msg("password") forgotPassword=realm.resetPasswordAllowed autofocus=true autocomplete="current-password" />
          <#else>
            <#assign label><#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if></#assign>
            <@field.input name="username" label=label error=messagesPerField.getFirstError('username','password') autofocus=true autocomplete="username" value=login.username!'' />
            <@field.password name="password" label=msg("password") error="" forgotPassword=realm.resetPasswordAllowed autofocus=false autocomplete="current-password">
              <#if realm.rememberMe><@field.checkbox name="rememberMe" label=msg("rememberMe") value=login.rememberMe?? /></#if>
            </@field.password>
          </#if>
          <input type="hidden" id="id-hidden-input" name="credentialId" <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if>/>
          <@buttons.loginButton />
        </form>
      </#if>
    </div></div>
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
</#if>
