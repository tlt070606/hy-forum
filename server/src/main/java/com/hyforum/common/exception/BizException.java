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
    }

    public BizException(ErrorCode errorCode, String detailMessage) {
        super(detailMessage);
        this.errorCode = errorCode;
    }

    /** 需要保留原始异常时使用（如把 SQL 异常翻译成业务异常）。 */
    public BizException(ErrorCode errorCode, String detailMessage, Throwable cause) {
        super(detailMessage, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    /** 业务异常不需要堆栈：它是预期内的流程分支，打全栈只会污染日志。 */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
