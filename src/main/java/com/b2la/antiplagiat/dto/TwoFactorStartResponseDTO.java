package com.b2la.antiplagiat.dto;

public record TwoFactorStartResponseDTO(
        String identifier,
        int expiresInMinutes
) {
}
