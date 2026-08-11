package com.b2la.antiplagiat.dto;

public record LoginRequestDTO(
        String identifier,
        String password,
        String username,
        String email,
        String phoneNumber
) {
    public String resolvedIdentifier() {
        if (identifier != null && !identifier.isBlank()) return identifier;
        if (username != null && !username.isBlank()) return username;
        if (email != null && !email.isBlank()) return email;
        if (phoneNumber != null && !phoneNumber.isBlank()) return phoneNumber;
        return null;
    }
}
