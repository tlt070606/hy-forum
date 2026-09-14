package com.hyforum.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * 统一响应体（docs/技术方案.md §6.1）：
 *
 * <pre>{ "code": 0, "message": "ok", "data": {} }</pre>
 *
 * <p>约定：</p>
 * <ul>
 *   <li>{@code code=0} 表示成功，非 0 一律为错误码（见 {@link ErrorCode}）。</li>
 *   <li>失败时 {@code data} 为 {@code null}；为减小报文体积用
 *       {@link JsonInclude.Include#NON_NULL} 省略该字段。</li>
 *   <li>业务失败**不改变 HTTP 状态语义**的部分（如 1001 用户名已存在）按契约仍返回 HTTP 200，
 *       由 {@link ErrorCode#httpStatus()} 统一决定，业务代码不自行指定。</li>
 * </ul>
 *
 * @param code    业务码
 * @param message 提示语
 * @param data    业务数据，可为 null
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiResponse<T>(int code, String message, T data) {

    /** 成功且无数据。 */
    public static ApiResponse<Void> ok() {
        return new ApiResponse<>(ErrorCode.SUCCESS.code(), ErrorCode.SUCCESS.message(), null);
    }

    /** 成功并携带数据。 */
    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(ErrorCode.SUCCESS.code(), ErrorCode.SUCCESS.message(), data);
    }

    /** 用错误码的默认提示语返回失败。 */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return new ApiResponse<>(errorCode.code(), errorCode.message(), null);
    }

    /** 用自定义提示语返回失败（码不变，只覆盖 message）。 */
    public static <T> ApiResponse<T> fail(ErrorCode errorCode, String message) {
        return new ApiResponse<>(errorCode.code(), message, null);
    }
}
