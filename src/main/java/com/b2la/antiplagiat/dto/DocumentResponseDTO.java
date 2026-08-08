package com.b2la.antiplagiat.dto;

import java.time.LocalDateTime;
import java.util.UUID;

public record DocumentResponseDTO(
        UUID id,
        String name,
        String faculty,
        String department,
        String author,
        String director,
        String rapporteur,
        String yearOfAcademic,
        String academic,
        String matriculation,
        LocalDateTime creationDate,
        UUID userId,
        String urlFile,
        String originalFileName,
        String contentType,
        long fileSize
) {
}
