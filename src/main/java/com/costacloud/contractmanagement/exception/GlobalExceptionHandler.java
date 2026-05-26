package com.costacloud.contractmanagement.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import io.minio.errors.MinioException;

@RestControllerAdvice(basePackages = "com.costacloud.contractmanagement")
public class GlobalExceptionHandler {

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationErrors(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .sorted((a, b) -> {
                    boolean aNotBlank = Arrays.stream(a.getCodes()).anyMatch(c -> c.startsWith("NotBlank"));
                    boolean bNotBlank = Arrays.stream(b.getCodes()).anyMatch(c -> c.startsWith("NotBlank"));
                    if (aNotBlank && !bNotBlank) return -1;
                    if (!aNotBlank && bNotBlank) return 1;
                    return 0;
                })
                .findFirst()
                .map(error -> error.getDefaultMessage())
                .orElse("Validation failed.");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(
                buildResponse(400, "Bad Request", message)
        );
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> handleRuntimeException(RuntimeException ex) {
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(
                buildResponse(401, "Unauthorized", ex.getMessage())
        );
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleException(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(
                buildResponse(500, "Internal Server Error", "Something went wrong. Please try again later.")
        );
    }

    @ExceptionHandler(MinioException.class)
    public ResponseEntity<Map<String, Object>> handleMinioException(MinioException ex) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(
                buildResponse(503, "Storage Error", "File storage operation failed. Please try again.")
        );
    }

    private Map<String, Object> buildResponse(int status, String error, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("status", status);
        response.put("error", error);
        response.put("message", message);
        return response;
    }
}
