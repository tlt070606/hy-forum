package com.hyforum.common.exception;

import com.hyforum.common.api.ApiResponse;
import com.hyforum.common.api.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;

/**
 * 全局异常处理：把任何异常都翻译成契约里的统一响应体（技术方案 §6.1）。
 *
 * <p><b>为什么必须统一</b>：只要有一条路径漏出去（例如默认的 Spring 错误响应
 * {@code {"timestamp":...,"status":500}}），前端就得写两套解析逻辑，契约也就名存实亡。
 * 因此这里对 {@link Exception} 兜底。</p>
 *
 * <p><b>日志纪律</b>（技术方案 §9「日志脱敏」）：业务异常按 WARN 记录且不打堆栈；
 * 非预期异常按 ERROR 记录完整堆栈。请求体一律不打印，避免把密码写进日志。</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** 业务异常：按枚举里的错误码与 HTTP 状态返回。 */
    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiResponse<Void>> handleBiz(BizException ex, HttpServletRequest request) {
        ErrorCode code = ex.errorCode();
        // 只记"哪个接口 + 哪个码"，不记请求体（可能含密码）
        log.warn("业务异常 {} {} -> code={} message={}",
                request.getMethod(), request.getRequestURI(), code.code(), ex.getMessage());
        return ResponseEntity.status(code.httpStatus())
                .body(ApiResponse.fail(code, ex.getMessage()));
    }

    /** @Valid 校验失败（请求体）→ 400 参数错误，message 带首个字段的提示，便于前端定位。 */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiResponse<Void>> handleInvalidBody(MethodArgumentNotValidException ex) {
        FieldError first = ex.getBindingResult().getFieldError();
        String message = first == null ? ErrorCode.BAD_REQUEST.message()
                : first.getField() + " " + first.getDefaultMessage();
        return badRequest(message);
    }

    /** 表单/参数对象绑定失败 → 400。 */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiResponse<Void>> handleBind(BindException ex) {
        FieldError first = ex.getBindingResult().getFieldError();
        String message = first == null ? ErrorCode.BAD_REQUEST.message()
                : first.getField() + " " + first.getDefaultMessage();
        return badRequest(message);
    }

    /** 请求体不是合法 JSON / 缺字段 → 400，而不是 500。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
        return badRequest(ErrorCode.BAD_REQUEST.message());
    }

    /** 没有对应 handler → 404（需配合 spring.mvc.throw-exception-if-no-handler-found=true）。 */
    @ExceptionHandler(NoHandlerFoundException.class)
    public ResponseEntity<ApiResponse<Void>> handleNoHandler(NoHandlerFoundException ex) {
        return ResponseEntity.status(ErrorCode.NOT_FOUND.httpStatus())
                .body(ApiResponse.fail(ErrorCode.NOT_FOUND));
    }

    /**
     * 兜底：任何未预期异常都不得把堆栈或 Spring 默认错误体暴露给前端。
     *
     * <p>注意 500 不在 §6.1 的 12 个契约码之内，属于 M1 的实现补充，
     * 已在交付报告中登记（见 {@link ErrorCode#INTERNAL_ERROR} 的注释）。</p>
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("未预期异常 {} {}", request.getMethod(), request.getRequestURI(), ex);
        return ResponseEntity.status(ErrorCode.INTERNAL_ERROR.httpStatus())
                .body(ApiResponse.fail(ErrorCode.INTERNAL_ERROR));
    }

    private ResponseEntity<ApiResponse<Void>> badRequest(String message) {
        return ResponseEntity.status(ErrorCode.BAD_REQUEST.httpStatus())
                .body(ApiResponse.fail(ErrorCode.BAD_REQUEST, message));
    }
}
