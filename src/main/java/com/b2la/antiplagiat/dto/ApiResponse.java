package com.b2la.antiplagiat.dto;

public record ApiResponse<T>(
        String status,
        String message,
        T data) {

    public ApiResponse(String status, T data) {
        this(status, null, data);
    }

    // Pour les erreurs sans données
    public ApiResponse(String status, String message) {
        this(status, message, null);
    }
}
