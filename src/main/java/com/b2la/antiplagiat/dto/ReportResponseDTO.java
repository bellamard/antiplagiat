package com.b2la.antiplagiat.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record ReportResponseDTO(
        UUID id,
        UUID analysisId,
        UUID documentId,
        UUID userId,
        String content,
        LocalDateTime createdAt
) {
}
