package com.noq.exception;

import java.time.LocalDateTime;
import java.util.Map;

/** The JSON body of every error response, so clients always get the same shape. */
public record ApiError(
        LocalDateTime timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> fieldErrors) {

    public static ApiError of(int status, String error, String message, String path) {
        return new ApiError(LocalDateTime.now(), status, error, message, path, null);
    }
}
