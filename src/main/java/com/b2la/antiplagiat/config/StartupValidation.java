package com.b2la.antiplagiat.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

@Component
public class StartupValidation implements ApplicationRunner {

    private static final Logger logger = LoggerFactory.getLogger(StartupValidation.class);
    private static final String DEFAULT_JWT_SECRET = "ChangeThisJwtSecretKeyWithAtLeast32Characters2026";
    private static final String DEFAULT_DB_PASSWORD = "password";

    private final Environment environment;
    private final String jwtSecret;
    private final String dbPassword;
    private final boolean requireTesseract;

    public StartupValidation(
            Environment environment,
            @Value("${app.jwt.secret:}") String jwtSecret,
            @Value("${spring.datasource.password:}") String dbPassword,
            @Value("${analysis.ocr.require-tesseract:false}") boolean requireTesseract
    ) {
        this.environment = environment;
        this.jwtSecret = jwtSecret;
        this.dbPassword = dbPassword;
        this.requireTesseract = requireTesseract;
    }

    @Override
    public void run(ApplicationArguments args) {
        validateProductionSecrets();
        validateTesseract();
    }

    private void validateProductionSecrets() {
        boolean production = Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> profile.equalsIgnoreCase("prod") || profile.equalsIgnoreCase("production"));
        if (!production) {
            return;
        }

        if (jwtSecret == null || jwtSecret.isBlank() || DEFAULT_JWT_SECRET.equals(jwtSecret)) {
            throw new IllegalStateException("JWT_SECRET obligatoire en production et différent de la valeur par défaut");
        }

        if (dbPassword == null || dbPassword.isBlank() || DEFAULT_DB_PASSWORD.equals(dbPassword)) {
            throw new IllegalStateException("DB_PASSWORD obligatoire en production et différent de la valeur par défaut");
        }
    }

    private void validateTesseract() {
        try {
            Process process = new ProcessBuilder("tesseract", "--version").start();
            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (finished && process.exitValue() == 0) {
                logger.info("Tesseract OCR detected");
                return;
            }
        } catch (Exception exception) {
            if (!requireTesseract) {
                logger.warn("Tesseract OCR not detected: {}", exception.getMessage());
                return;
            }
        }

        if (requireTesseract) {
            throw new IllegalStateException("Tesseract OCR est requis mais introuvable");
        }
        logger.warn("Tesseract OCR not detected");
    }
}
