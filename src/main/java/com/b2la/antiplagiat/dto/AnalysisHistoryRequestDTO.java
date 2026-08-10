package com.b2la.antiplagiat.dto;

import java.util.UUID;

public record AnalysisHistoryRequestDTO(
        UUID documentId,
        double overallScore,
        double aiScore,
        String details
) {
}
