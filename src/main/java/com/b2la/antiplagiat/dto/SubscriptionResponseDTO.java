package com.b2la.antiplagiat.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record SubscriptionResponseDTO(
        UUID id,
        String type,
        int quotaAntiPlagiarism,
        int quotaAntiAi,
        UUID userId,
        String username,
        boolean active,
        LocalDateTime startAt,
        LocalDateTime endAt,
        LocalDateTime createdAt
) {
}
