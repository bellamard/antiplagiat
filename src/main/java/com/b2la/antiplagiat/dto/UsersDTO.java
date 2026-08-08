package com.b2la.antiplagiat.dto;

import java.time.LocalDate;

public record UsersDTO(
        String username,
        String email,
        String password,
        String firstName,
        String lastName,
        String surname,
        String phoneNumber,
        LocalDate dateOfBirth
) {
}
