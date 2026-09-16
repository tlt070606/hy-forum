package com.hyforum.media.oss;

/**
 * OSS 回调验签的结果。
 *
 * <p>为什么不是裸 {@code boolean}：验签失败有<b>好几种完全不同的原因</b>
 * （公钥地址不在允许范围内、取公钥失败、签名不匹配、请求过旧……），
 * 而它们的处置方式不同 —— SSRF 尝试要告警，时钟偏差要调配置，
 * 伪造签名要视为攻击。用一个只带"是/否"的返回值，唯一的后果就是
 * 运维在排障时只能看到 {@code 403}，然后开始猜。</p>
 *
 * <p>{@code reason} <b>只进日志，绝不进响应体</b>（§6.1：响应体只给通用文案）——
 * 对攻击者暴露"你差在哪一步"等于送一份调参指南。</p>
 *
 * @param passed 是否通过
 * @param reason 失败原因（通过时为 {@code "ok"}）
 */
public record OssVerifyResult(boolean passed, String reason) {

    /** 通过。 */
    public static OssVerifyResult pass() {
        return new OssVerifyResult(true, "ok");
    }

    /** 失败。 */
    public static OssVerifyResult fail(String reason) {
        return new OssVerifyResult(false, reason);
    }
}
