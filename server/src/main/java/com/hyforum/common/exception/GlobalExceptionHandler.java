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
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
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

        // 业务动作维度的限流要 HTTP 200 + 业务码（§5 裁决 #6/#8 的分层），
        // 而 ErrorCode.TOO_MANY_REQUESTS 自带 429 —— 因此异常可以覆盖 HTTP 状态。
        // 未覆盖时（绝大多数）仍按 ErrorCode.httpStatus() 走，**行为与从前一字不差**。
        org.springframework.http.HttpStatus httpStatus =
                ex.httpStatusOverride() != null ? ex.httpStatusOverride() : code.httpStatus();
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(httpStatus);
        // ★ H2：429 带 Retry-After 头（验收项 SEC_rate_limit_returns_429_with_retry_after）。
        //
        // 这是本方法里**唯一的新增**（L1 给的边界：只做"把 retryAfter 传到响应头"这一件事，
        // 不顺手改其它异常分支 —— 那是 M1 的地盘，且本项目已因"顺手改"出过事）。
        //
        // 为什么必须由异常把它带过来：限流器拒绝时（RateLimiter.check）知道"还要等几秒"，
        // 而到了这一层那个信息已经丢了。让这里反向去查 Redis 也不行 ——
        // 多一次 IO，且查到的值已经不是"拒绝那一刻"的值。
        //
        // 为什么 null / ≤0 时**不设头**：设 "Retry-After: 0" 会让客户端**立刻重试**，
        // 等于把一次拒绝放大成一串重试，比不设头更糟。
        // （"兜底给个 0"看起来很自然，所以这条值得写下来。）
        Long retryAfter = ex.retryAfterSeconds();
        if (retryAfter != null && retryAfter > 0) {
            builder.header("Retry-After", String.valueOf(retryAfter));
        }
        return builder.body(ApiResponse.fail(code, ex.getMessage()));
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

    /**
     * 路径变量 / 请求参数<b>类型不匹配</b> → 400（交接事项 <b>H12</b>，2026-09-16 补）。
     *
     * <p>背景：{@code GET /api/posts/abc}（{@code id} 声明为 {@code long}）会把
     * {@link MethodArgumentTypeMismatchException} 一路冒到 {@link #handleUnexpected}，
     * 于是"客户端把参数写错了"被报成 {@code 500 服务器内部错误}。
     * 后果不是文案难看，而是<b>5xx 是监控口径里的异常信号</b> ——
     * 被这类必然发生的误用污染后，真故障会被淹没；前端也会按 5xx 去重试与告警。</p>
     *
     * <p><b>只回参数名，不回参数值</b>：值来自请求，回显它等于把用户输入反射进响应体
     * （§6.1 的约束是"响应体只给通用文案"，细节写服务端日志）。
     * 这里刻意连 WARN 都不打 —— 它是纯粹的用户输入错误，不是需要运维介入的信号。</p>
     *
     * <p>刻意<b>不</b>顺手处理其它 Spring MVC 异常（缺参数、请求方法不支持等）：
     * 本分支是 H12 点名的缺陷修复；范围一放开，"改一个异常分支"就变成"重写全局异常策略"。</p>
     */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiResponse<Void>> handleArgumentTypeMismatch(MethodArgumentTypeMismatchException ex) {
        return badRequest("参数 " + ex.getName() + " 类型不合法");
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
