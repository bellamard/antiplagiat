package com.b2la.antiplagiat.service;

import com.b2la.antiplagiat.dto.AuthResponseDTO;
import com.b2la.antiplagiat.dto.LoginRequestDTO;
import com.b2la.antiplagiat.dto.TwoFactorStartResponseDTO;
import com.b2la.antiplagiat.dto.TwoFactorVerifyDTO;
import com.b2la.antiplagiat.entites.Users;
import com.b2la.antiplagiat.mapper.UsersDTOMapper;
import com.b2la.antiplagiat.repository.UsersRepository;
import jakarta.transaction.Transactional;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Transactional
public class AuthService {

    private static final int TWO_FACTOR_EXPIRATION_MINUTES = 10;

    private final UsersRepository usersRepository;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final MailService mailService;
    private final UsersDTOMapper usersDTOMapper;
    private final SecureRandom secureRandom = new SecureRandom();
    private final Map<String, TwoFactorCode> pendingCodes = new ConcurrentHashMap<>();

    public AuthService(
            UsersRepository usersRepository,
            BCryptPasswordEncoder passwordEncoder,
            JwtService jwtService,
            MailService mailService,
            UsersDTOMapper usersDTOMapper
    ) {
        this.usersRepository = usersRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.mailService = mailService;
        this.usersDTOMapper = usersDTOMapper;
    }

    public TwoFactorStartResponseDTO startLogin(LoginRequestDTO request) {
        Users user = findByIdentifier(request.identifier());

        if (request.password() == null || !passwordEncoder.matches(request.password(), user.getPassword())) {
            throw new IllegalArgumentException("Identifiants invalides");
        }

        String code = generateCode();
        Instant expiresAt = Instant.now().plus(TWO_FACTOR_EXPIRATION_MINUTES, ChronoUnit.MINUTES);
        pendingCodes.put(user.getUsername(), new TwoFactorCode(code, expiresAt));
        mailService.sendTwoFactorCode(user.getEmail(), code);

        return new TwoFactorStartResponseDTO(user.getUsername(), TWO_FACTOR_EXPIRATION_MINUTES);
    }

    public AuthResponseDTO verifyLogin(TwoFactorVerifyDTO request) {
        Users user = findByIdentifier(request.identifier());
        TwoFactorCode twoFactorCode = pendingCodes.get(user.getUsername());

        if (twoFactorCode == null || twoFactorCode.isExpired() || !twoFactorCode.code().equals(request.code())) {
            throw new IllegalArgumentException("Code de vérification invalide ou expiré");
        }

        pendingCodes.remove(user.getUsername());

        return new AuthResponseDTO(
                jwtService.generateToken(user),
                "Bearer",
                jwtService.getExpirationInstant(),
                usersDTOMapper.apply(user)
        );
    }

    private Users findByIdentifier(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            throw new IllegalArgumentException("Identifiant obligatoire");
        }

        return usersRepository.findByUsername(identifier)
                .or(() -> usersRepository.findByEmail(identifier))
                .orElseThrow(() -> new IllegalArgumentException("Identifiants invalides"));
    }

    private String generateCode() {
        return String.format("%06d", secureRandom.nextInt(1_000_000));
    }

    private record TwoFactorCode(String code, Instant expiresAt) {
        private boolean isExpired() {
            return Instant.now().isAfter(expiresAt);
        }
    }
}
