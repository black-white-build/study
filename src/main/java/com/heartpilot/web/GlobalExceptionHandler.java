package com.heartpilot.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException exception, HttpServletRequest request) {
        return response(
                exception.status(), exception.code(), exception.getMessage(), Map.of(), request);
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> denied(AccessDeniedException exception, HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权执行此操作", Map.of(), request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ApiError> validation(
            MethodArgumentNotValidException exception, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception
                .getBindingResult()
                .getFieldErrors()
                .forEach(error -> fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求参数校验失败", fields, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    ResponseEntity<ApiError> constraint(
            ConstraintViolationException exception, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception
                .getConstraintViolations()
                .forEach(
                        violation ->
                                fields.put(
                                        violation.getPropertyPath().toString(),
                                        violation.getMessage()));
        return response(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "请求参数校验失败", fields, request);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    ResponseEntity<ApiError> optimisticLock(
            ObjectOptimisticLockingFailureException exception, HttpServletRequest request) {
        return response(
                HttpStatus.CONFLICT,
                "CONCURRENT_MODIFICATION",
                "任务已被其他请求更新，请刷新后重试",
                Map.of(),
                request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> conflict(
            DataIntegrityViolationException exception, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "DATA_CONFLICT", "请求与现有数据冲突", Map.of(), request);
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unknown(Exception exception, HttpServletRequest request) {
        String traceId = traceId();
        log.error("Unhandled request error, traceId={}", traceId, exception);
        return ResponseEntity.internalServerError()
                .body(
                        new ApiError(
                                "INTERNAL_ERROR",
                                "服务暂时不可用，请稍后重试",
                                Instant.now(),
                                request.getRequestURI(),
                                traceId,
                                Map.of()));
    }

    private ResponseEntity<ApiError> response(
            HttpStatus status,
            String code,
            String message,
            Map<String, String> fields,
            HttpServletRequest request) {
        return ResponseEntity.status(status)
                .body(
                        new ApiError(
                                code,
                                message,
                                Instant.now(),
                                request.getRequestURI(),
                                traceId(),
                                fields));
    }

    private String traceId() {
        String value = MDC.get("traceId");
        return value == null || value.isBlank()
                ? UUID.randomUUID().toString().substring(0, 12)
                : value;
    }

    public record ApiError(
            String code,
            String message,
            Instant timestamp,
            String path,
            String traceId,
            Map<String, String> fieldErrors) {}
}
