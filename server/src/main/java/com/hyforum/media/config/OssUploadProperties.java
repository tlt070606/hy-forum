package com.hyforum.media.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * OSS 直传（签名）配置（前缀 {@code hy.oss.upload}）。
 *
 * <p>这些键<b>不在 {@code application.yml} 里</b>（该文件由 L1 维护，本段只读），
 * 因此取值来自这里的<b>代码默认值</b>，需要调整时用环境变量覆盖
 * （Spring 的宽松绑定：{@code hy.oss.upload.signature-ttl-seconds}
 * ← {@code HY_OSSUPLOAD_SIGNATURETTLSECONDS} 或 {@code -Dhy.oss.upload...}）。
 * 若将来要落成正式配置项，由 L1 决定（本段已把这一点写进交付报告）。</p>
 *
 * @param signatureTtlSeconds 签名的有效期（秒）。默认 600：够用户选图与填表单，
 *                            又短到"泄露一个 policy 也用不了多久"。policy 的
 *                            {@code expiration} 与响应里的 {@code expire} 由它推导
 * @param callbackUrl         回调<b>公网</b>地址（OSS 要能访问到）。
 *                            默认空 = <b>从当前请求推导</b>（与健康检查里
 *                            {@code servers[0].url} 同源：取请求进来的 scheme+host）。
 *                            本机开发要收真实回调时必须显式指向隧道地址，
 *                            因为后端本地监听 127.0.0.1，OSS 够不到（见 OSS配置清单 §5）
 * @param callbackBodyType    回调消息体的 Content-Type，默认 {@code application/json}
 * @param callbackBody        回调消息体模板（OSS 会替换其中的 {@code ${...}} 变量）。
 *                            刻意<b>把每个值都加引号</b>：变量为空时 JSON 仍然合法，
 *                            而一个 JSON 解析失败的回调会被当成"验签/落库失败"，
 *                            排查方向会被带偏
 * @param successActionStatus 直传成功时 OSS 返回给前端的 HTTP 状态码（204 无响应体，200 有）
 */
@ConfigurationProperties(prefix = "hy.oss.upload")
public record OssUploadProperties(
        int signatureTtlSeconds,
        String callbackUrl,
        String callbackBodyType,
        String callbackBody,
        int successActionStatus) {

    /** 默认签名有效期（秒）。 */
    private static final int DEFAULT_TTL_SECONDS = 600;

    public OssUploadProperties {
        if (signatureTtlSeconds <= 0) {
            signatureTtlSeconds = DEFAULT_TTL_SECONDS;
        }
        if (callbackBodyType == null || callbackBodyType.isBlank()) {
            callbackBodyType = "application/json";
        }
        if (callbackBody == null || callbackBody.isBlank()) {
            // ⚠️ 模板里的 ${...} **一律不加引号**，因为 OSS 替换时：
            //    · 字符串变量（object / bucket / mimeType / etag）**自带 JSON 引号**；
            //    · 数值变量（size）不带引号。
            // 实测证据（2026-09-16 真实回调，见交付报告证据 #6）：
            // 早期模板写成 "object":"${object}"，OSS 替换后得到的是
            //   {"object":""post/2026/...png"", ...}   ← 双重引号，不是合法 JSON
            // 而 OSS 只会把它报成 `CallbackFailed: Error status : 400`，
            // 看起来完全不像"JSON 拼坏了"，排查成本极高。这条踩坑记录必须留在代码里。
            callbackBody = "{\"object\":${object},\"bucket\":${bucket},\"size\":${size},"
                    + "\"mimeType\":${mimeType},\"etag\":${etag}}";
        }
        if (successActionStatus != 200 && successActionStatus != 201 && successActionStatus != 204) {
            // OSS 只接受这三个值；给别的值它会把整个上传判失败
            successActionStatus = 200;
        }
    }

    /** 回调地址是否由运维显式指定（false = 从请求推导）。供日志与报告使用。 */
    public boolean hasExplicitCallbackUrl() {
        return callbackUrl != null && !callbackUrl.isBlank();
    }

    /** 签名有效期（Duration 形态，便于与 {@code Instant} 运算）。 */
    public Duration signatureTtl() {
        return Duration.ofSeconds(signatureTtlSeconds);
    }
}
