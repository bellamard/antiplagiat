package com.b2la.antiplagiat.dto;

import java.time.LocalDateTime;

public record SubscriptionRequestDTO(
        String type,
        LocalDateTime startAt,
        LocalDateTime endAt
) {
}
