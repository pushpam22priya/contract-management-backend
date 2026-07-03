package com.costacloud.contractmanagement.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import jakarta.mail.internet.MimeMessage;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);
    private static final DateTimeFormatter DATE_FMT  = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy");
    private static final DateTimeFormatter SHORT_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    private final JavaMailSender mailSender;

    @Value("${spring.mail.username}")
    private String fromAddress;

    @Value("${app.base-url}")
    private String baseUrl;

    public EmailService(JavaMailSender mailSender) {
        this.mailSender = mailSender;
    }

    // ─── Email 1: Signature Request ──────────────────────────────

    public void sendSignatureRequestEmail(String toEmail, String toName,
                                          String senderName, String senderEmail,
                                          String contractTitle, String signingUrl,
                                          LocalDateTime expiresAt,
                                          String partyLabel, String partyColor) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromAddress);
            helper.setReplyTo(senderEmail);
            helper.setTo(toEmail);
            helper.setSubject("\"" + contractTitle + "\" is ready for your signature");

            String expiryLong  = expiresAt != null ? expiresAt.format(DATE_FMT)  : "7 days from now";
            String expiryShort = expiresAt != null ? expiresAt.format(SHORT_FMT) : "7 days";
            String sentDate    = LocalDateTime.now().format(DATE_FMT);
            String color       = (partyColor != null && !partyColor.isBlank()) ? partyColor : "#0e7c6b";
            String label       = (partyLabel != null && !partyLabel.isBlank()) ? partyLabel : "";

            String plain = (toName != null && !toName.isBlank() ? "Hi " + toName : "Hi there") + ",\n\n"
                    + senderName + " has sent you a contract for your digital signature.\n\n"
                    + "Party    : " + label + "\n"
                    + "Contract : " + contractTitle + "\n"
                    + "Sent by  : " + senderName + " (" + senderEmail + ")\n"
                    + "Expires  : " + expiryShort + "\n\n"
                    + "Review & Sign: " + signingUrl + "\n\n"
                    + "This link expires on " + expiryShort + ". After signing, you will receive "
                    + "a copy of the signed document by email.\n\n"
                    + "If you have questions, reply to this email — replies go directly to " + senderName + ".";

            helper.setText(plain, buildSignatureRequestHtml(
                    contractTitle, senderName, senderEmail, sentDate,
                    signingUrl, expiryLong, label, color));
            mailSender.send(message);

        } catch (MailException | jakarta.mail.MessagingException e) {
            log.error("Failed to send signature request email to {}: {}", toEmail, e.getMessage(), e);
            throw new RuntimeException("Email delivery failed for " + toEmail + ": " + e.getMessage(), e);
        }
    }

    // ─── Email 2: Signed Copy (after finalization) ───────────────

    public void sendSignedCopyEmail(String toEmail, String toName,
                                    String contractTitle, String downloadUrl) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromAddress);
            helper.setTo(toEmail);
            helper.setSubject("Signed copy: \"" + contractTitle + "\"");

            String greeting = (toName != null && !toName.isBlank()) ? "Hi " + toName : "Hi there";

            String plain = greeting + ",\n\n"
                    + "All parties have signed \"" + contractTitle + "\".\n"
                    + "A copy of the fully signed document is available at the link below.\n\n"
                    + "Download: " + downloadUrl + "\n\n"
                    + "This link is valid for 7 days.";

            helper.setText(plain, buildSignedCopyHtml(toName, contractTitle, downloadUrl));
            mailSender.send(message);

        } catch (MailException | jakarta.mail.MessagingException e) {
            log.error("Failed to send signed copy email to {}: {}", toEmail, e.getMessage());
        }
    }

    // ─── HTML Builders ────────────────────────────────────────────

    private String buildSignatureRequestHtml(String contractTitle, String senderName,
                                             String senderEmail, String sentDate,
                                             String signingUrl, String expiryDate,
                                             String partyLabel, String partyColor) {
        // Party badge row — only rendered when a label is present
        String partyBadgeRow = partyLabel.isBlank() ? "" : """
                              <tr>
                                <td style="padding:0 0 12px;">
                                  <span style="display:inline-block;
                                               border:1.5px solid %s;
                                               color:%s;
                                               background-color:#ffffff;
                                               border-radius:4px;
                                               padding:4px 12px;
                                               font-size:12px;
                                               font-weight:700;
                                               letter-spacing:0.4px;">
                                    Assigned Party : %s
                                  </span>
                                </td>
                              </tr>
                """.formatted(partyColor, partyColor, partyLabel);

        return """
                <!DOCTYPE html>
                <html lang="en">
                <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:0;padding:0;background-color:#f4f6f8;font-family:Arial,Helvetica,sans-serif;">
                  <table width="100%%" cellpadding="0" cellspacing="0" style="background-color:#f4f6f8;padding:32px 0;">
                    <tr><td align="center">
                      <table width="600" cellpadding="0" cellspacing="0"
                             style="background-color:#ffffff;border-radius:8px;overflow:hidden;
                                    box-shadow:0 2px 12px rgba(0,0,0,0.08);max-width:600px;">

                        <!-- Header -->
                        <tr>
                          <td style="background:linear-gradient(135deg,#0e7c6b 0%%,#14967c 100%%);
                                     padding:32px 40px;text-align:center;">
                            <h1 style="color:#ffffff;margin:0;font-size:22px;font-weight:700;letter-spacing:0.5px;">
                              Document Signature Request
                            </h1>
                          </td>
                        </tr>

                        <!-- Body -->
                        <tr>
                          <td style="padding:36px 40px;">

                            <p style="color:#374151;font-size:16px;margin:0 0 8px;">Hello,</p>
                            <p style="color:#374151;font-size:15px;margin:0 0 24px;line-height:1.6;">
                              You have been requested to review and sign the following document:
                            </p>

                            <!-- Contract Card — border color matches party color -->
                            <table width="100%%" cellpadding="0" cellspacing="0"
                                   style="background-color:#f8fafb;border-radius:6px;
                                          border-left:4px solid %s;margin:0 0 28px;">
                              <tr>
                                <td style="padding:20px 24px;">
                                  <table width="100%%" cellpadding="0" cellspacing="0">
                                    %s
                                    <tr>
                                      <td>
                                        <p style="margin:0 0 10px;font-size:17px;font-weight:700;color:#111827;">%s</p>
                                        <p style="margin:0 0 5px;font-size:14px;color:#6b7280;">
                                          Requested by:&nbsp;
                                          <a href="mailto:%s" style="color:#0e7c6b;text-decoration:none;font-weight:600;">%s</a>
                                        </p>
                                        <p style="margin:0;font-size:14px;color:#6b7280;">Sent on: %s</p>
                                      </td>
                                    </tr>
                                  </table>
                                </td>
                              </tr>
                            </table>

                            <p style="color:#374151;font-size:15px;margin:0 0 24px;line-height:1.6;">
                              Please click the button below to review and sign the document:
                            </p>

                            <!-- CTA Button -->
                            <table cellpadding="0" cellspacing="0" style="margin:0 0 28px;">
                              <tr>
                                <td style="background-color:#0e7c6b;border-radius:6px;">
                                  <a href="%s"
                                     style="display:inline-block;padding:14px 36px;color:#ffffff;
                                            font-size:15px;font-weight:700;text-decoration:none;letter-spacing:0.4px;">
                                    Review &amp; Sign Document
                                  </a>
                                </td>
                              </tr>
                            </table>

                            <!-- Expiry Notice -->
                            <table width="100%%" cellpadding="0" cellspacing="0"
                                   style="background-color:#fffbeb;border:1px solid #fcd34d;
                                          border-radius:6px;margin:0 0 28px;">
                              <tr>
                                <td style="padding:14px 18px;">
                                  <p style="margin:0;font-size:14px;color:#92400e;">
                                    &#9200;&nbsp;<strong>This link will expire on:</strong>&nbsp;%s
                                  </p>
                                </td>
                              </tr>
                            </table>

                            <p style="color:#9ca3af;font-size:13px;margin:0 0 6px;line-height:1.5;">
                              If you did not expect this email or have concerns, please contact the sender directly at
                              <a href="mailto:%s" style="color:#0e7c6b;">%s</a>.
                            </p>
                            <p style="color:#9ca3af;font-size:13px;margin:0;line-height:1.5;">
                              If you have questions, reply to this email — replies go directly to %s.
                            </p>

                          </td>
                        </tr>

                        <!-- Footer -->
                        <tr>
                          <td style="background-color:#f9fafb;padding:18px 40px;text-align:center;
                                     border-top:1px solid #e5e7eb;">
                            <p style="margin:0;font-size:12px;color:#d1d5db;">
                              CostaCloud Contract Management &nbsp;|&nbsp; This is an automated message.
                            </p>
                          </td>
                        </tr>

                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(
                partyColor,        // card border-left color
                partyBadgeRow,     // party badge row (or empty string)
                contractTitle,
                senderEmail, senderEmail,
                sentDate,
                signingUrl,
                expiryDate,
                senderEmail, senderEmail,
                senderName
        );
    }

    private String buildSignedCopyHtml(String toName, String contractTitle, String downloadUrl) {
        String greeting = (toName != null && !toName.isBlank()) ? "Hi " + toName + "," : "Hi there,";
        return """
                <!DOCTYPE html>
                <html lang="en">
                <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
                <body style="margin:0;padding:0;background-color:#f4f6f8;font-family:Arial,Helvetica,sans-serif;">
                  <table width="100%%" cellpadding="0" cellspacing="0" style="background-color:#f4f6f8;padding:32px 0;">
                    <tr><td align="center">
                      <table width="600" cellpadding="0" cellspacing="0"
                             style="background-color:#ffffff;border-radius:8px;overflow:hidden;
                                    box-shadow:0 2px 12px rgba(0,0,0,0.08);max-width:600px;">

                        <!-- Header -->
                        <tr>
                          <td style="background:linear-gradient(135deg,#0e7c6b 0%%,#14967c 100%%);
                                     padding:32px 40px;text-align:center;">
                            <p style="margin:0 0 10px;font-size:40px;">&#10003;</p>
                            <h1 style="color:#ffffff;margin:0;font-size:22px;font-weight:700;letter-spacing:0.5px;">
                              Document Fully Signed
                            </h1>
                          </td>
                        </tr>

                        <!-- Body -->
                        <tr>
                          <td style="padding:36px 40px;">

                            <p style="color:#374151;font-size:16px;margin:0 0 8px;">%s</p>
                            <p style="color:#374151;font-size:15px;margin:0 0 24px;line-height:1.6;">
                              All parties have signed <strong>"%s"</strong>.
                              A copy of the fully signed document is ready for download.
                            </p>

                            <!-- Contract Card -->
                            <table width="100%%" cellpadding="0" cellspacing="0"
                                   style="background-color:#f8fafb;border-radius:6px;
                                          border-left:4px solid #0e7c6b;margin:0 0 28px;">
                              <tr>
                                <td style="padding:20px 24px;">
                                  <p style="margin:0 0 6px;font-size:17px;font-weight:700;color:#111827;">%s</p>
                                  <p style="margin:0;font-size:14px;color:#6b7280;">
                                    Status:&nbsp;<span style="color:#0e7c6b;font-weight:600;">Fully Executed</span>
                                  </p>
                                </td>
                              </tr>
                            </table>

                            <!-- Download Button -->
                            <table cellpadding="0" cellspacing="0" style="margin:0 0 28px;">
                              <tr>
                                <td style="background-color:#0e7c6b;border-radius:6px;">
                                  <a href="%s"
                                     style="display:inline-block;padding:14px 36px;color:#ffffff;
                                            font-size:15px;font-weight:700;text-decoration:none;letter-spacing:0.4px;">
                                    &#11015;&nbsp; Download Signed Document
                                  </a>
                                </td>
                              </tr>
                            </table>

                            <!-- Expiry Notice -->
                            <table width="100%%" cellpadding="0" cellspacing="0"
                                   style="background-color:#fffbeb;border:1px solid #fcd34d;
                                          border-radius:6px;margin:0 0 28px;">
                              <tr>
                                <td style="padding:14px 18px;">
                                  <p style="margin:0;font-size:14px;color:#92400e;">
                                    &#9200;&nbsp;<strong>Download link is valid for 7 days.</strong>
                                  </p>
                                </td>
                              </tr>
                            </table>

                            <p style="color:#9ca3af;font-size:13px;margin:0;line-height:1.5;">
                              Please save a copy of the signed document for your records.
                            </p>

                          </td>
                        </tr>

                        <!-- Footer -->
                        <tr>
                          <td style="background-color:#f9fafb;padding:18px 40px;text-align:center;
                                     border-top:1px solid #e5e7eb;">
                            <p style="margin:0;font-size:12px;color:#d1d5db;">
                              CostaCloud Contract Management &nbsp;|&nbsp; This is an automated message.
                            </p>
                          </td>
                        </tr>

                      </table>
                    </td></tr>
                  </table>
                </body>
                </html>
                """.formatted(greeting, contractTitle, contractTitle, downloadUrl);
    }
}
