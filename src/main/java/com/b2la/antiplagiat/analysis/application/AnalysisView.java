package com.b2la.antiplagiat.analysis.application;

import java.time.LocalDateTime;
import java.util.UUID;

public record AnalysisView(
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
