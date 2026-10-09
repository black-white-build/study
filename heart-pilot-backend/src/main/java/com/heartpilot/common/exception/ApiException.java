package com.heartpilot.common.exception;

import org.springframework.http.HttpStatus;

/**
 * 业务异常统一基类。 携带 HTTP 状态码与业务错误码，由 GlobalExceptionHandler 捕获后转换为统一错误响应体， 便于前端按 code 做分支处理，而不是解析 HTTP
 * 状态码。
 */
public class ApiException extends RuntimeException {
    /** 对应的 HTTP 状态码，决定响应状态行 */
    private final HttpStatus status;

    /** 业务错误码（如 NOT_FOUND / BAD_REQUEST），供前端程序化判断 */
    private final String code;

    /**
     * 构造业务异常。
     *
     * @param status HTTP 状态码
     * @param code 业务错误码
     * @param message 面向用户的错误描述
     */
    public ApiException(HttpStatus status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    /**
     * @return 该异常对应的 HTTP 状态码
     */
    public HttpStatus status() {
        return status;
    }

    /**
     * @return 业务错误码
     */
    public String code() {
        return code;
    }

    /**
     * 快捷构造 404 NOT_FOUND 异常。
     *
     * @param message 资源未找到的描述
     */
    public static ApiException notFound(String message) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", message);
    }

    /**
     * 快捷构造 400 BAD_REQUEST 异常。
     *
     * @param message 请求非法的描述
     */
    public static ApiException badRequest(String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, "BAD_REQUEST", message);
    }

    /**
     * 快捷构造 409 CONFLICT 冲突异常。
     *
     * @param code 具体冲突业务码（如唯一性冲突的业务标识）
     * @param message 冲突描述
     */
    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }
}
