package com.b2la.antiplagiat.dto;

import java.time.LocalDate;

public record UsersResponseDTO(
    String username,
    String firstname,
    String lastname,
    String surname,
    String Role,
    String phone
) {
}
