package com.b2la.antiplagiat.dto;

public record TwoFactorVerifyDTO(
        String identifier,
        String code
) {
}
