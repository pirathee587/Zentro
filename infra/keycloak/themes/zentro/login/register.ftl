<#import "template.ftl" as layout>
<#import "field.ftl" as field>
<#import "register-commons.ftl" as registerCommons>
<#import "password-validation.ftl" as validator>
<@layout.registrationLayout displayMessage=messagesPerField.exists('global') displayRequiredFields=true; section>
  <#if section = "header">
    <span class="zentro-login-brand"><span class="zentro-login-mark">z</span>zentro</span>
    <span class="zentro-login-title"><#if messageHeader??>${msg(messageHeader)}<#else>${msg("registerTitle")}</#if></span>
    <span class="zentro-login-subtitle">Create your account with your email and a secure password. We will verify your email before you can sign in.</span>
  <#elseif section = "form">
    <form id="kc-register-form" class="${properties.kcFormClass!}" action="${url.registrationAction}" method="post" novalidate="novalidate">
      <div class="${properties.kcFormGroupClass!}">
        <label class="${properties.kcLabelClass!}" for="email">${msg("email")}</label>
        <input id="email" class="${properties.kcInputClass!}" name="email" type="email" value="${(register.formData.email!'')}" autocomplete="email" autofocus required aria-invalid="${messagesPerField.exists('email')?string('true', 'false')}" />
        <#if messagesPerField.exists('email')>
          <span class="${properties.kcInputErrorMessageClass!}" role="alert">${kcSanitize(messagesPerField.get('email'))?no_esc}</span>
        </#if>
      </div>
      <#if passwordRequired??>
        <@field.password name="password" required=true label=msg("password") autocomplete="new-password" />
        <@field.password name="password-confirm" required=true label=msg("passwordConfirm") autocomplete="new-password" />
      </#if>
      <@registerCommons.termsAcceptance/>
      <div id="kc-form-buttons" class="${properties.kcFormButtonsClass!}">
        <input class="${properties.kcButtonClass!} ${properties.kcButtonPrimaryClass!} ${properties.kcButtonBlockClass!} ${properties.kcButtonLargeClass!}" type="submit" value="${msg("doRegister")}"/>
      </div>
      <p class="zentro-verification-note" role="note">After registration, check your Gmail inbox for the verification email. Verify it to activate your Zentro account.</p>
      <div class="${properties.kcFormGroupClass!} pf-v5-c-login__main-footer-band"><div id="kc-form-options" class="zentro-register-signin-links"><a data-zentro-signin-return href="http://localhost:5173/login">Sign in</a></div></div>
    </form>
    <@validator.templates/><@validator.script field="password"/>
  </#if>
</@layout.registrationLayout>
