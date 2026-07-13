package com.shelfj.notification.channel;

import jakarta.mail.Authenticator;
import jakarta.mail.Message;
import jakarta.mail.PasswordAuthentication;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.Properties;

/**
 * SMTP delivery via Jakarta Mail. Selected when {@code shelfj.notification.channel=smtp}.
 * Credentials and host come from config/secret store (never committed). A send failure throws so
 * the consumer loop retries.
 */
public final class SmtpChannel implements NotificationChannel {

  private final String host;
  private final int port;
  private final String username;
  private final String password;
  private final String from;
  private final boolean startTls;

  public SmtpChannel(
      String host, int port, String username, String password, String from, boolean startTls) {
    this.host = host;
    this.port = port;
    this.username = username;
    this.password = password;
    this.from = from;
    this.startTls = startTls;
  }

  @Override
  public String name() {
    return "SMTP";
  }

  @Override
  public void send(String recipient, String subject, String body) {
    boolean auth = username != null && !username.isBlank();
    Properties props = new Properties();
    props.put("mail.smtp.host", host);
    props.put("mail.smtp.port", String.valueOf(port));
    props.put("mail.smtp.auth", String.valueOf(auth));
    props.put("mail.smtp.starttls.enable", String.valueOf(startTls));

    Session session =
        auth
            ? Session.getInstance(
                props,
                new Authenticator() {
                  @Override
                  protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(username, password);
                  }
                })
            : Session.getInstance(props);

    try {
      MimeMessage msg = new MimeMessage(session);
      msg.setFrom(new InternetAddress(from));
      msg.setRecipient(Message.RecipientType.TO, new InternetAddress(recipient));
      msg.setSubject(subject);
      msg.setText(body);
      Transport.send(msg);
    } catch (jakarta.mail.MessagingException e) {
      throw new IllegalStateException(
          "SMTP send to " + recipient + " failed: " + e.getMessage(), e);
    }
  }
}
