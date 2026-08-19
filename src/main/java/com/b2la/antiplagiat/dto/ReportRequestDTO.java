package com.b2la.antiplagiat.dto;

import java.util.UUID;

public record ReportRequestDTO(
        UUID analysisId,
        UUID documentId,
        Boolean clearBase64Content
) {
}
