package com.b2la.antiplagiat.service;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class MailService {

    private final JavaMailSender mailSender;
    private final boolean mailEnabled;
    private final String from;

    public MailService(
            ObjectProvider<JavaMailSender> mailSender,
            @Value("${app.mail.enabled:false}") boolean mailEnabled,
            @Value("${spring.mail.username:no-reply@antiplagiat.local}") String from
    ) {
        this.mailSender = mailSender.getIfAvailable();
        this.mailEnabled = mailEnabled;
        this.from = from;
    }

    public void sendTwoFactorCode(String to, String code) {
        if (!mailEnabled || mailSender == null) {
            System.out.println("Code 2FA pour " + to + " : " + code);
            return;
        }

        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(from);
        message.setTo(to);
        message.setSubject("Code de connexion Antiplagiat");
        message.setText("Votre code de connexion est : " + code + "\nCe code expire dans 10 minutes.");
        mailSender.send(message);
    }
}
