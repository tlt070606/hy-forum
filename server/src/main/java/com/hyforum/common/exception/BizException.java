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
    }

    public BizException(ErrorCode errorCode, String detailMessage) {
        super(detailMessage);
        this.errorCode = errorCode;
        this.retryAfterSeconds = null;
    }

    /** 需要保留原始异常时使用（如把 SQL 异常翻译成业务异常）。 */
    public BizException(ErrorCode errorCode, String detailMessage, Throwable cause) {
        super(detailMessage, cause);
        this.errorCode = errorCode;
        this.retryAfterSeconds = null;
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
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** {@code Retry-After} 秒数；不需要时为 {@code null}。 */
    public Long retryAfterSeconds() {
        return retryAfterSeconds;
    }

    /** 业务异常不需要堆栈：它是预期内的流程分支，打全栈只会污染日志。 */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
