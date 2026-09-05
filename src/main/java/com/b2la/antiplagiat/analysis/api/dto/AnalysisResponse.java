package com.b2la.antiplagiat.analysis.api.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record AnalysisResponse(
        UUID id,
        UUID documentId,
        String documentName,
        UUID userId,
        String username,
        double overallScore,
        double aiScore,
        String status,
        String failedStep,
        String errorMessage,
        int attempts,
        int maxAttempts,
        String details,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime createdAt
) {
}
