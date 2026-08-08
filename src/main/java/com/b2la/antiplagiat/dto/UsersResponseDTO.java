package com.b2la.antiplagiat.dto;

import com.b2la.antiplagiat.enumerote.Role;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

public record UsersResponseDTO(
        UUID id,
        String username,
        String email,
        String firstName,
        String lastName,
        String surname,
        String phoneNumber,
        Role role,
        LocalDate dateOfBirth,
        LocalDateTime createdAt
) {
}
