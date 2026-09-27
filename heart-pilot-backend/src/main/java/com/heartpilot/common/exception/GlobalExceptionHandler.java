package com.heartpilot.common.exception;

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

/**
 * 全局异常处理器。
 * 通过 @RestControllerAdvice 统一拦截 Controller 层抛出的各类异常，
 * 转换为结构一致的 ApiError JSON 响应（含 traceId 便于日志排查），
 * 避免堆栈信息直接暴露给前端。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /**
     * 处理业务异常 ApiException。
     * 直接使用异常自带的 HTTP 状态码与业务码，返回对应的错误描述。
     */
    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException exception, HttpServletRequest request) {
        return response(
                exception.status(), exception.code(), exception.getMessage(), Map.of(), request);
    }

    /**
     * 处理 Spring Security 权限不足异常 AccessDeniedException。
     * 返回 403 FORBIDDEN，文案统一为"无权执行此操作"。
     */
    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> denied(AccessDeniedException exception, HttpServletRequest request) {
        return response(HttpStatus.FORBIDDEN, "FORBIDDEN", "无权执行此操作", Map.of(), request);
    }

    /**
     * 处理 @RequestBody 参数校验失败异常 MethodArgumentNotValidException。
     * 返回 400 VALIDATION_ERROR，并汇总每个字段的第一条错误信息（putIfAbsent 去重）。
     */
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

    /**
     * 处理方法参数/路径变量校验失败异常 ConstraintViolationException。
     * 与上一个方法类似，返回 400 VALIDATION_ERROR，字段名为约束违反的属性路径。
     */
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

    /**
     * 处理 JPA 乐观锁并发冲突异常 ObjectOptimisticLockingFailureException。
     * 返回 409 CONCURRENT_MODIFICATION，提示用户刷新后重试（@Version 版本号冲突）。
     */
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

    /**
     * 处理数据库完整性约束异常 DataIntegrityViolationException（唯一键/外键等违反）。
     * 返回 409 DATA_CONFLICT，避免把数据库底层错误细节暴露给前端。
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<ApiError> conflict(
            DataIntegrityViolationException exception, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, "DATA_CONFLICT", "请求与现有数据冲突", Map.of(), request);
    }

    /**
     * 兜底处理所有未被前面方法捕获的异常。
     * 记录完整堆栈日志（含 traceId），返回 500 INTERNAL_ERROR，对前端只给友好提示。
     */
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

    /**
     * 统一构造错误响应体的私有方法。
     */
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

    /**
     * 获取当前请求的 traceId：优先取 MDC 中的链路 ID，缺失时临时生成一个短 UUID 兜底。
     */
    private String traceId() {
        String value = MDC.get("traceId");
        return value == null || value.isBlank()
                ? UUID.randomUUID().toString().substring(0, 12)
                : value;
    }

    /**
     * 统一错误响应体结构 record。
     */
    public record ApiError(
            /** 业务错误码 */
            String code,
            /** 面向用户的错误描述 */
            String message,
            /** 错误发生时间 */
            Instant timestamp,
            /** 出错的请求路径 */
            String path,
            /** 链路追踪 ID，用于日志关联 */
            String traceId,
            /** 字段级校验错误（字段名 → 错误信息），无则为空 Map */
            Map<String, String> fieldErrors) {}
}
