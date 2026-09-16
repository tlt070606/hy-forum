package com.hyforum.media.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * OSS <b>凭据</b>配置（前缀 {@code aliyun.oss}）—— 只在 {@code media} 包内绑定。
 *
 * <h2>为什么单独一个类，而不是并进 {@code common.oss.OssProperties}</h2>
 * <p>2026-09-16 L1 裁决：{@code OssProperties} 上移到 {@code common} 供 {@code post} 与
 * {@code media} 共用，但<b>凭据不进那个类</b>。这是<b>最小暴露</b>，不是洁癖：
 * {@code post} 也注入 {@code OssProperties}，而它只需要 endpoint / bucket / imageDir
 * 三个非密钥项。密钥被不相关的模块读到之后，将来就会有人在不相关的地方用它 ——
 * 而"哪里用了密钥"这件事，一旦扩散就无法用静态检查收敛。</p>
 *
 * <h2>{@code @NotBlank} 的意义：把"启动即失败"那句注释兑现</h2>
 * <p>{@code application.yml} 的 OSS 一节写着「两个密钥**故意留空**：没设环境变量时启动即失败」。
 * 但 2026-09-16 L1 实测：<b>不设任何 OSS 环境变量，应用照样启动成功并在 8080 正常服务</b> ——
 * 也就是说那句话当时是<b>假的</b>。本类用 {@code @Validated + @NotBlank} 把它兑现：
 * 密钥为空时 Spring 上下文<b>直接起不来</b>，错误信息指向缺失的环境变量。</p>
 *
 * <p>为什么"快速失败"更好：否则应用会带着空密钥正常启动，直到<b>第一个用户点上传</b>时才报错 ——
 * 那时错误暴露在业务路径上（用户看到上传失败），而部署者以为一切正常。
 * 启动期失败是唯一能在"发布"这一步就被发现的位置。</p>
 *
 * <p><b>测试怎么办</b>：{@code server/src/test/resources/application-test.yml} 里有两个
 * <b>测试占位值</b>（明确写着不是真实密钥）。不补它们的话，所有 {@code @SpringBootTest}
 * 上下文都会起不来，M1 与 M3 第一段的 44 个用例会集体变红 —— 而那与被测行为毫无关系。
 * 这就是"fail-fast 必须与测试配置配套"的含义。</p>
 *
 * @param accessKeyId     RAM 子账号的 AccessKey ID，来自环境变量 {@code OSS_ACCESS_KEY_ID}
 * @param accessKeySecret RAM 子账号的 AccessKey Secret，来自环境变量 {@code OSS_ACCESS_KEY_SECRET}
 */
@ConfigurationProperties(prefix = "aliyun.oss")
@Validated
public record OssCredentialProperties(
        @NotBlank(message = "缺少环境变量 OSS_ACCESS_KEY_ID（OSS 直传签名需要它）")
        String accessKeyId,

        @NotBlank(message = "缺少环境变量 OSS_ACCESS_KEY_SECRET（OSS 直传签名需要它）")
        String accessKeySecret) {

    /**
     * 覆写 toString：record 默认的 toString 会打印<b>全部字段值</b>，而这里是密钥。
     *
     * <p>一旦有人把本对象打进日志（{@code log.info("cfg={}", props)}）、
     * 或它作为异常消息的一部分被打印，密钥就进了日志文件 —— 而日志常被收集、
     * 上传、甚至贴进对话。所以这里显式遮蔽它，只回长度。</p>
     */
    @Override
    public String toString() {
        return "OssCredentialProperties[accessKeyId=" + mask(accessKeyId)
                + ", accessKeySecret=" + mask(accessKeySecret) + "]";
    }

    /** 只暴露"有没有 / 多长"，绝不暴露取值本身。 */
    private static String mask(String secret) {
        return secret == null ? "(空)" : "(长度 " + secret.length() + ")";
    }
}
