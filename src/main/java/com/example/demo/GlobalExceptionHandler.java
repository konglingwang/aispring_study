package com.example.demo;

import java.util.Map;

import org.springframework.ai.retry.NonTransientAiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Unified error handling for all endpoints: returns a consistent JSON error
 * body instead of Spring's default whitelabel error page.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<Map<String, Object>> missingParam(MissingServletRequestParameterException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(errorBody(400, "Missing required parameter: " + ex.getParameterName()));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(errorBody(400, ex.getMessage()));
    }

    // NonTransientAiException: model API errors that retrying won't fix
    // (e.g. 404 wrong path, 401 bad key) - surfaces the real cause to the client.
    @ExceptionHandler(NonTransientAiException.class)
    public ResponseEntity<Map<String, Object>> aiError(NonTransientAiException ex) {
        String msg = ex.getMessage() != null ? ex.getMessage() : "AI model call failed";
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(errorBody(502, "Model service error: " + msg));
    }

    // Client disconnected mid-SSE-stream: nothing left to write to, and Spring
    // wraps the broken async request in this exception. Must NOT try to write
    // a JSON error body (the response is text/event-stream, would throw
    // HttpMessageNotWritableException and loop back into the handler chain).
    @ExceptionHandler(AsyncRequestNotUsableException.class)
    public void asyncGone(AsyncRequestNotUsableException ex) {
        // swallow: the browser is gone, no response is possible
    }

    // Static resources that don't exist must stay 404 (the catch-all below
    // would otherwise swallow this and turn every missing file into a 500,
    // breaking <script onerror> CDN fallbacks for the frontend).
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(errorBody(404, "Not found: " + ex.getResourcePath()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> generic(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(errorBody(500, "Internal error: " + ex.getClass().getSimpleName()));
    }

    private Map<String, Object> errorBody(int status, String message) {
        return Map.of("status", status, "error", message, "timestamp", System.currentTimeMillis());
    }
}
