package com.b2la.antiplagiat.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record ScoreResponseDTO(
        UUID id,
        double overallScore,
        double aiScore,
        UUID documentId,
        String documentName,
        UUID userId,
        String username,
        LocalDateTime createdAt
) {
}
