package com.hyforum.common.api;

import org.springframework.http.HttpStatus;

/**
 * 接口错误码契约。
 *
 * <p><b>唯一来源</b>：docs/技术方案.md §6.1 —— 本枚举是那份契约在代码上的<b>逐条落地</b>，
 * 一个不多、一个不少。任何人都不得在业务代码里直接写裸数字，必须引用本枚举
 * （由 {@code M1_error_codes_match_contract} 测试守门）。</p>
 *
 * <p>其中 {@code 2001}/{@code 2002} 虽由 M5（内容安全）与 M3（发帖限流）最终触发，
 * 但错误码本身属于 §6.1 的通用约定，因此 M1 就把枚举定义完整，避免后续模块
 * 各自造码造成漂移。</p>
 */
public enum ErrorCode {

    /** 成功。响应体 {@code {"code":0,"message":"ok","data":...}}。 */
    SUCCESS(0, "ok", HttpStatus.OK),

    /** 400 参数错误：校验失败、缺少必填项等。 */
    BAD_REQUEST(400, "参数错误", HttpStatus.BAD_REQUEST),

    /** 401 未登录：token 缺失或失效。 */
    UNAUTHORIZED(401, "未登录", HttpStatus.UNAUTHORIZED),

    /** 403 无权限：已登录但无权执行（含注册模式 closed 下拒绝注册）。 */
    FORBIDDEN(403, "无权限", HttpStatus.FORBIDDEN),

    /** 404 资源不存在。 */
    NOT_FOUND(404, "资源不存在", HttpStatus.NOT_FOUND),

    /** 429 请求过于频繁（技术方案 §8.7 频率限制）。 */
    TOO_MANY_REQUESTS(429, "请求过于频繁", HttpStatus.TOO_MANY_REQUESTS),

    /** 1001 用户名已存在。 */
    USERNAME_EXISTS(1001, "用户名已存在", HttpStatus.OK),

    /** 1002 用户名或密码错误。 */
    BAD_CREDENTIALS(1002, "用户名或密码错误", HttpStatus.OK),

    /** 1003 验证码错误（含验证码不存在/已过期，与契约一致：统一按"错误"处理，不泄露细节）。 */
    CAPTCHA_INVALID(1003, "验证码错误", HttpStatus.OK),

    /** 1004 账号已被封禁，或邀请码无效/已失效/已过期。 */
    ACCOUNT_DISABLED(1004, "账号已被封禁", HttpStatus.OK),

    /** 2001 内容包含敏感词。 */
    SENSITIVE_CONTENT(2001, "内容包含敏感词", HttpStatus.OK),

    /** 2002 发帖过于频繁（触发方为 M3 的发帖接口，错误码在 M1 定义）。 */
    POST_TOO_FREQUENT(2002, "发帖过于频繁", HttpStatus.OK),

    /**
     * 500 服务器内部错误。
     *
     * <p><b>本码是 M1 的实现补充，不在 §6.1 的 12 个契约码之内</b>（已在交付报告中登记为需 L1 裁决项）。
     * 理由：未捕获异常必须有一个确定的对外表现，否则会泄露堆栈或返回非统一响应体，
     * 破坏"所有响应都是统一响应体"这一硬约定。取 500 是为了与 HTTP 语义一致，
     * 且不会与任何已定义的业务码冲突。</p>
     */
    INTERNAL_ERROR(500, "服务器内部错误", HttpStatus.INTERNAL_SERVER_ERROR);

    private final int code;
    private final String message;
    private final HttpStatus httpStatus;

    ErrorCode(int code, String message, HttpStatus httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    /** 业务码：写入响应体的 {@code code} 字段。 */
    public int code() {
        return code;
    }

    /** 默认提示语：写入响应体的 {@code message} 字段。 */
    public String message() {
        return message;
    }

    /** 对应的 HTTP 状态码。 */
    public HttpStatus httpStatus() {
        return httpStatus;
    }

    /** 是否成功。 */
    public boolean isSuccess() {
        return this == SUCCESS;
    }

    /**
     * 按业务码反查枚举，用于测试与日志；找不到抛异常而不是返回 null，
     * 以免调用方静默拿到 null 继续往下走。
     */
    public static ErrorCode of(int code) {
        for (ErrorCode each : values()) {
            if (each.code == code) {
                return each;
            }
        }
        throw new IllegalArgumentException("未知错误码：" + code);
    }
}
