package com.zentro.identitypartner.email;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class EmailSenderService {
  private static final Logger log = LoggerFactory.getLogger(EmailSenderService.class);

  private final JavaMailSender mailSender;
  private final String fromEmail;
  private final String appBaseUrl;

  public EmailSenderService(
      JavaMailSender mailSender,
      @Value("${zentro.mail.from:tkirusigan@gmail.com}") String fromEmail,
      @Value("${zentro.app-base-url:http://localhost}") String appBaseUrl) {
    this.mailSender = mailSender;
    this.fromEmail = fromEmail;
    this.appBaseUrl = appBaseUrl.replaceAll("/+$", "");
  }

  public void sendCourierInvite(String toEmail, String courierName, String tempPassword) {
    String subject = "Welcome to Zentro - Courier Account Details";
    String loginUrl = appBaseUrl + "/staff/login";
    String html = """
        <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; max-width: 580px; margin: 0 auto; padding: 24px; color: #231d19; background-color: #faf7f2; border-radius: 16px;">
          <div style="display: flex; align-items: center; margin-bottom: 24px;">
            <div style="width: 36px; height: 36px; border-radius: 9999px; background: #231d19; color: #f4d7a9; display: inline-grid; place-items: center; font-family: Georgia, serif; font-size: 22px; text-align: center; line-height: 36px; margin-right: 10px;">z</div>
            <strong style="font-size: 22px; letter-spacing: -0.5px;">zentro</strong>
          </div>
          <div style="background: #ffffff; padding: 32px; border-radius: 14px; border: 1px solid #ebdccb; box-shadow: 0 4px 12px rgba(35, 29, 25, 0.04);">
            <h2 style="margin-top: 0; color: #231d19; font-size: 22px;">Welcome to the Team, %s!</h2>
            <p style="color: #63584e; font-size: 14px; line-height: 1.6;">You have been registered as a delivery courier for Zentro. Here are your temporary login credentials:</p>
            <div style="background: #f5eee2; border-left: 4px solid #ad7f46; padding: 16px; margin: 20px 0; border-radius: 6px;">
              <p style="margin: 0 0 8px; font-size: 14px;"><strong>Email:</strong> %s</p>
              <p style="margin: 0; font-size: 14px;"><strong>Temporary Password:</strong> <code style="font-size: 16px; font-weight: bold; color: #ad7f46; background: #ffffff; padding: 3px 8px; border-radius: 4px; border: 1px solid #d6c5ac;">%s</code></p>
            </div>
            <p style="color: #63584e; font-size: 13px; line-height: 1.6;"><strong>Security Notice:</strong> For your protection, you will be prompted to set a new password on your first sign-in.</p>
            <div style="margin: 28px 0 12px; text-align: center;">
              <a href="%s" style="display: inline-block; background: #231d19; color: #ffffff; text-decoration: none; padding: 14px 28px; border-radius: 999px; font-weight: bold; font-size: 14px;">Sign in to Courier Portal</a>
            </div>
          </div>
          <p style="color: #998b7a; font-size: 12px; text-align: center; margin-top: 24px;">Thoughtful delivery, made for Sri Lanka.</p>
        </div>
        """.formatted(courierName, toEmail, tempPassword, loginUrl);

    sendHtml(toEmail, subject, html);
  }

  public void sendPasswordResetOtp(String toEmail, String otpCode) {
    String subject = otpCode + " is your Zentro password reset code";
    String html = """
        <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; max-width: 540px; margin: 0 auto; padding: 24px; color: #231d19; background-color: #faf7f2; border-radius: 16px;">
          <div style="display: flex; align-items: center; margin-bottom: 24px;">
            <div style="width: 36px; height: 36px; border-radius: 9999px; background: #231d19; color: #f4d7a9; display: inline-grid; place-items: center; font-family: Georgia, serif; font-size: 22px; text-align: center; line-height: 36px; margin-right: 10px;">z</div>
            <strong style="font-size: 22px; letter-spacing: -0.5px;">zentro</strong>
          </div>
          <div style="background: #ffffff; padding: 32px; border-radius: 14px; border: 1px solid #ebdccb; box-shadow: 0 4px 12px rgba(35, 29, 25, 0.04);">
            <h2 style="margin-top: 0; color: #231d19; font-size: 20px;">Password Reset Request</h2>
            <p style="color: #63584e; font-size: 14px; line-height: 1.6;">Use the verification code below to reset your password. This code is valid for 10 minutes.</p>
            <div style="margin: 28px 0; text-align: center;">
              <span style="display: inline-block; font-size: 36px; font-weight: 800; letter-spacing: 8px; color: #231d19; background: #faf7f2; padding: 14px 28px; border-radius: 12px; border: 1px dashed #ad7f46;">%s</span>
            </div>
            <p style="color: #63584e; font-size: 13px; line-height: 1.6;">If you did not request a password reset, please ignore this email or contact support if you have concerns.</p>
          </div>
          <p style="color: #998b7a; font-size: 12px; text-align: center; margin-top: 24px;">Thoughtful delivery, made for Sri Lanka.</p>
        </div>
        """.formatted(otpCode);

    sendHtml(toEmail, subject, html);
  }

  public void sendRegistrationOtp(String toEmail, String otpCode) {
    String subject = otpCode + " is your Zentro verification code";
    String html = """
        <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; max-width: 580px; margin: 0 auto; padding: 24px; color: #231d19; background-color: #faf7f2; border-radius: 16px;">
          <div style="display: flex; align-items: center; margin-bottom: 24px;">
            <div style="width: 36px; height: 36px; border-radius: 9999px; background: #231d19; color: #f4d7a9; display: inline-grid; place-items: center; font-family: Georgia, serif; font-size: 22px; text-align: center; line-height: 36px; margin-right: 10px;">z</div>
            <strong style="font-size: 22px; letter-spacing: -0.5px;">zentro</strong>
          </div>
          <div style="background: #ffffff; padding: 32px; border-radius: 14px; border: 1px solid #ebdccb; box-shadow: 0 4px 12px rgba(35, 29, 25, 0.04); text-align: center;">
            <h2 style="margin-top: 0; color: #231d19; font-size: 22px;">Confirm your email address</h2>
            <p style="color: #63584e; font-size: 14px; line-height: 1.6;">Use the verification code below to complete your Zentro registration:</p>
            <div style="margin: 28px auto; display: inline-block; padding: 14px 32px; background: #f5eee2; border: 2px dashed #ad7f46; border-radius: 12px;">
              <span style="font-size: 32px; font-weight: 700; letter-spacing: 8px; color: #231d19; font-family: monospace;">%s</span>
            </div>
            <p style="color: #63584e; font-size: 13px; line-height: 1.6;">This code is valid for <strong>10 minutes</strong>. Do not share this code with anyone.</p>
          </div>
          <p style="color: #998b7a; font-size: 12px; text-align: center; margin-top: 24px;">If you didn't create a Zentro account, you can safely ignore this email.</p>
        </div>
        """.formatted(otpCode);

    sendHtml(toEmail, subject, html);
  }

  public void sendMerchantApproved(String toEmail, String businessName, String dashboardUrl) {
    String subject = "🎉 Your Zentro Merchant Application is Approved!";
    String targetUrl = dashboardUrl != null && !dashboardUrl.isBlank() ? dashboardUrl : appBaseUrl + "/merchant/dashboard";
    String html = """
        <div style="font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, Helvetica, Arial, sans-serif; max-width: 580px; margin: 0 auto; padding: 24px; color: #231d19; background-color: #faf7f2; border-radius: 16px;">
          <div style="display: flex; align-items: center; margin-bottom: 24px;">
            <div style="width: 36px; height: 36px; border-radius: 9999px; background: #231d19; color: #f4d7a9; display: inline-grid; place-items: center; font-family: Georgia, serif; font-size: 22px; text-align: center; line-height: 36px; margin-right: 10px;">z</div>
            <strong style="font-size: 22px; letter-spacing: -0.5px;">zentro</strong>
          </div>
          <div style="background: #ffffff; padding: 32px; border-radius: 14px; border: 1px solid #ebdccb; box-shadow: 0 4px 12px rgba(35, 29, 25, 0.04);">
            <div style="display: inline-block; background: #e6f6ee; color: #0e6245; padding: 4px 12px; border-radius: 999px; font-size: 12px; font-weight: bold; margin-bottom: 16px;">APPLICATION APPROVED</div>
            <h2 style="margin-top: 0; color: #231d19; font-size: 22px;">Congratulations, %s!</h2>
            <p style="color: #63584e; font-size: 14px; line-height: 1.6;">Your business registration has been reviewed and approved by our dispatch team. Your merchant account is now fully active.</p>
            <p style="color: #63584e; font-size: 14px; line-height: 1.6;">You can now access the Zentro Merchant Portal to manage orders, schedule pickups, and integrate APIs.</p>
            <div style="margin: 28px 0 12px; text-align: center;">
              <a href="%s" style="display: inline-block; background: #231d19; color: #ffffff; text-decoration: none; padding: 14px 28px; border-radius: 999px; font-weight: bold; font-size: 14px;">Open Merchant Dashboard</a>
            </div>
            <p style="color: #998b7a; font-size: 12px; text-align: center; margin-top: 14px;">Or copy and paste this link: <br/><a href="%s" style="color: #ad7f46;">%s</a></p>
          </div>
          <p style="color: #998b7a; font-size: 12px; text-align: center; margin-top: 24px;">Thoughtful delivery, made for Sri Lanka.</p>
        </div>
        """.formatted(businessName, targetUrl, targetUrl, targetUrl);

    sendHtml(toEmail, subject, html);
  }

  private void sendHtml(String toEmail, String subject, String htmlContent) {
    try {
      MimeMessage message = mailSender.createMimeMessage();
      MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
      helper.setFrom(fromEmail, "Zentro Delivery");
      helper.setTo(toEmail);
      helper.setSubject(subject);
      helper.setText(htmlContent, true);

      mailSender.send(message);
      log.info("Sent email '{}' to {}", subject, toEmail);
    } catch (Exception ex) {
      log.error("Failed to send email '{}' to {}: {}", subject, toEmail, ex.getMessage(), ex);
    }
  }
}

