package com.b2la.antiplagiat.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record AnalysisHistoryResponseDTO(
        UUID id,
        UUID documentId,
        String documentName,
        UUID userId,
        String username,
        double overallScore,
        double aiScore,
        String details,
        LocalDateTime createdAt
) {
}
