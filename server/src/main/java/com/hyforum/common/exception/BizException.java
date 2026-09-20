package com.hyforum.common.exception;

import com.hyforum.common.api.ErrorCode;

/**
 * 业务异常：所有可以预期、需要按契约错误码返回给前端的失败都用它抛出。
 *
 * <p>为什么不用 {@code RuntimeException} + 字符串：错误码是契约（技术方案 §6.1），
 * 用枚举承载可以让编译器保证"码是契约里有的"，并且能被全局异常处理器统一翻译成
 * 统一响应体，业务代码不必关心 HTTP 状态码（由 {@link ErrorCode#httpStatus()} 决定）。</p>
 *
 * <p>约定：</p>
 * <ul>
 *   <li>构造时传 {@link ErrorCode} 即使用该码的默认提示语；</li>
 *   <li>需要更具体的提示时用带 {@code detailMessage} 的构造器，
 *       <b>但不得借它泄露内部信息</b>（例如验证码错误不得区分"不存在"与"填错"）。</li>
 * </ul>
 */
public class BizException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;

    public BizException(ErrorCode errorCode) {
        super(errorCode.message());
        this.errorCode = errorCode;
        this.retryAfterSeconds = null;
        this.httpStatusOverride = null;
    }

    public BizException(ErrorCode errorCode, String detailMessage) {
        super(detailMessage);
        this.errorCode = errorCode;
        this.retryAfterSeconds = null;
        this.httpStatusOverride = null;
    }

    /** 需要保留原始异常时使用（如把 SQL 异常翻译成业务异常）。 */
    public BizException(ErrorCode errorCode, String detailMessage, Throwable cause) {
        super(detailMessage, cause);
        this.errorCode = errorCode;
        this.retryAfterSeconds = null;
        this.httpStatusOverride = null;
    }

    /**
     * {@code Retry-After} 的秒数；<b>只有 429 需要它</b>，其余情况为 {@code null}。
     *
     * <p>为什么放在异常上（而不是让 GlobalExceptionHandler 去猜）：限流器
     * （{@code RateLimiter.check}）在拒绝的那一刻<b>知道</b>窗口还剩多少秒
     * （{@code Decision.retryAfter()}），而到了异常处理器那一层这个信息已经丢了 ——
     * 除非有人把它带过来。**带在异常上是唯一不丢信息的做法**，
     * 也不需要异常处理器反向去查 Redis（那会多一次 IO，且查到的值已经不是拒绝时的那个）。</p>
     */
    private final Long retryAfterSeconds;

    /**
     * 带 {@code Retry-After} 的业务异常（H2）。
     *
     * <p><b>只应该用于 {@link ErrorCode#TOO_MANY_REQUESTS}</b>：契约里只有 429
     * 对应"稍后重试"这个语义（技术方案 §6.1 / §8.7）。
     * 刻意不在构造器里强制校验这一点：那会把"错误码与语义是否匹配"从评审问题变成运行期异常，
     * 而这层校验的价值远小于它在别处被误用时的困惑。</p>
     *
     * @param retryAfterSeconds 建议的重试等待秒数；{@code null} 或 ≤0 时<b>不设该头</b>
     *                          （设一个 "0" 比不设更糟：它会让客户端立刻重试，等于放大流量）
     */
    public BizException(ErrorCode errorCode, String detailMessage, Long retryAfterSeconds) {
        super(detailMessage);
        this.errorCode = errorCode;
        this.retryAfterSeconds = retryAfterSeconds;
        this.httpStatusOverride = null;
    }

    /** 见 {@link #httpStatusOverride()}。 */
    private final org.springframework.http.HttpStatus httpStatusOverride;

    private BizException(ErrorCode errorCode, String detailMessage, Long retryAfterSeconds,
                         org.springframework.http.HttpStatus httpStatusOverride) {
        super(detailMessage);
        this.errorCode = errorCode;
        this.retryAfterSeconds = retryAfterSeconds;
        this.httpStatusOverride = httpStatusOverride;
    }

    /**
     * 业务动作维度的限流异常：<b>业务码不变、HTTP 状态改成 200</b>。
     *
     * <p>为什么需要它（§5 裁决 #6／#8 的"分层"）：契约把限流分成两层，
     * 而两层的**HTTP 状态不同**：</p>
     * <ul>
     *   <li><b>入口维度</b>（登录/注册按 IP）→ HTTP <b>429</b>；</li>
     *   <li><b>业务动作维度</b>（发帖 2002、举报 ≤10 次/天）→ <b>HTTP 200</b> + 业务码。</li>
     * </ul>
     * <p>但"请求过于频繁"这个业务码在 {@link ErrorCode#TOO_MANY_REQUESTS} 上
     * <b>自带 429</b>（{@code ErrorCode} 把码与 HTTP 状态绑在一起，这是 M1 冻结的契约）。
     * 于是"想用 429 这个业务码、但要 HTTP 200"在原来做不到 —— 除非新增一个错误码
     * （要走契约变更流程，本任务不许）或复用语义不对的 {@code 2002}
     * （那会让前端把举报限流显示成"发帖太频繁"，**语义错误比缺信息更糟**）。</p>
     *
     * <p>因此这里给异常一个"HTTP 状态覆盖"的口子：<b>业务码仍是契约里的 429，
     * HTTP 状态按分层给 200</b>。它只有这一个用法，所以做成静态工厂而不是公开构造器 ——
     * 避免被用到别的场景（那会让"HTTP 状态从哪来"变得难以推理）。</p>
     *
     * @param errorCode   业务码
     * @param detailMessage 提示语（应说明是**每日**上限等用户能据以行动的信息）
     */
    public static BizException businessActionRateLimited(ErrorCode errorCode, String detailMessage) {
        return new BizException(errorCode, detailMessage, null,
                org.springframework.http.HttpStatus.OK);
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** {@code Retry-After} 秒数；不需要时为 {@code null}。 */
    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /**
     * HTTP 状态覆盖；{@code null} 表示按 {@link ErrorCode#httpStatus()} 走（默认，绝大多数情况）。
     *
     * @see #businessActionRateLimited(ErrorCode, String)
     */
    public org.springframework.http.HttpStatus httpStatusOverride() {
        return httpStatusOverride;
    }

    /** 业务异常不需要堆栈：它是预期内的流程分支，打全栈只会污染日志。 */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
