<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "user-profile-commons.ftl" as userProfileCommons>
<#import "register-commons.ftl" as registerCommons>
<#import "password-validation.ftl" as validator>
<@layout.registrationLayout displayMessage=messagesPerField.exists('global') displayRequiredFields=true; section>
  <#if section = "header">
    <span class="zentro-login-brand"><span class="zentro-login-mark">z</span>zentro</span>
    <span class="zentro-login-title"><#if messageHeader??>${msg(messageHeader)}<#else>${msg("registerTitle")}</#if></span>
    <span class="zentro-login-subtitle">Create your account to send and track parcels across Sri Lanka.</span>
  <#elseif section = "form">
    <form id="kc-register-form" class="${properties.kcFormClass!}" action="${url.registrationAction}" method="post" novalidate="novalidate">
      <@userProfileCommons.userProfileFormFields; callback, attribute>
        <#-- User details render before the password fields. -->
      </@userProfileCommons.userProfileFormFields>
      <#if passwordRequired??>
        <@field.password name="password" required=true label=msg("password") autocomplete="new-password" />
        <@field.password name="password-confirm" required=true label=msg("passwordConfirm") autocomplete="new-password" />
      </#if>
      <@registerCommons.termsAcceptance/>
      <div id="kc-form-buttons" class="${properties.kcFormButtonsClass!}">
        <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}" type="submit" value="${msg("doRegister")}"/>
      </div>
      <div class="${properties.kcFormGroupClass!} pf-v5-c-login__main-footer-band"><div id="kc-form-options" class="zentro-register-signin-links"><a data-zentro-signin-return href="http://localhost:5173/login">Sign in</a></div></div>
    </form>
    <@validator.templates/><@validator.script field="password"/>
  </#if>
</@layout.registrationLayout>
