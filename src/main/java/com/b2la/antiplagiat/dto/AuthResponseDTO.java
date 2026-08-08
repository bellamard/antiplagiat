package com.b2la.antiplagiat.dto;

import java.time.Instant;

public record AuthResponseDTO(
        String token,
        String tokenType,
        Instant expiresAt,
        UsersResponseDTO user
) {
}
