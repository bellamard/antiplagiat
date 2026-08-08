package com.b2la.antiplagiat.dto;

import java.util.UUID;

public record ScoreRequestDTO(
        UUID documentId,
        double overallScore,
        double aiScore
) {
}
