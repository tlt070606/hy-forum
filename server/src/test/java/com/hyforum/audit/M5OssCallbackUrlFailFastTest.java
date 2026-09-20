package com.hyforum.audit;

import com.hyforum.media.config.OssCallbackUrlResolver;
import com.hyforum.media.config.OssUploadProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code OSS_CALLBACK_URL} 的 <b>fail-fast</b>（M5 任务书 §9；需求方 2026-09-20 裁决）。
 *
 * <h2>为什么不能写成 {@code @SpringBootTest}</h2>
 * <p>本用例要验的行为是"<b>上下文起不来</b>"。而 {@code @SpringBootTest} 的语义是
 * "把这个应用的上下文起起来" —— 两者直接冲突：要么应用起不来导致用例报错
 * （而那是**预期的**，不是失败），要么为了让上下文能起就得开降级（那就验不到 fail 路径）。
 * 因此用 Spring Boot 的 {@link ApplicationContextRunner}：<b>按用例给定属性、按需构建</b>
 * 一个只含被测 Bean 的最小上下文，然后断言"构建成功"或"构建失败 + 失败原因"。</p>
 *
 * <h2>被测的是"启动门"，三条路径都要有</h2>
 * <ol>
 *   <li><b>红</b>：不设 {@code OSS_CALLBACK_URL}、不开降级 → <b>构建失败</b>，且错误信息里
 *       写清"怎么修"（只说"配置缺失"会让人去翻文档）；</li>
 *   <li><b>绿</b>：设了公网 URL → 构建成功；</li>
 *   <li><b>降级</b>：不设、但显式开环回开关 → 也成功（证明降级是<b>显式</b>的，不是默认放行）。</li>
 * </ol>
 * <p>另加一条：<b>配了环回地址且未开降级 → 也必须失败</b>（显式写错也要拦住 ——
 * "本机地址被写进配置"正是当初那次事故的形态）。</p>
 */
class M5OssCallbackUrlFailFastTest {

    /** 测试用的公网形态 URL（不需要真的可达：本用例只验启动门，不验回调）。 */
    private static final String PUBLIC_CALLBACK = "https://forum.example.com/api/oss/callback";

    /**
     * 只装被测 Bean 的最小上下文（不引整个应用：那会牵进 30 多个 Bean 与数据源）。
     *
     * <p>{@code OssUploadProperties} 是个 record，**不能无参 new** ——
     * 所以用 {@code @EnableConfigurationProperties} 让 Spring 按属性绑定去构造它
     * （这也更接近生产接线：生产里它同样是被绑定的）。</p>
     */
    @Configuration
    @org.springframework.boot.context.properties.EnableConfigurationProperties(OssUploadProperties.class)
    static class MinimalConfig {
        @Bean
        OssCallbackUrlResolver ossCallbackUrlResolver(OssUploadProperties properties,
                                                     org.springframework.core.env.Environment env) {
            // 与生产接线一致：两个输入分别是"配置项"与"环境变量别名"，
            // 这里从 Environment 读，以便用例用 property 名模拟环境变量 OSS_CALLBACK_URL
            return new OssCallbackUrlResolver(
                    properties,
                    env.getProperty("OSS_CALLBACK_URL", ""),
                    env.getProperty("hy.oss.upload.allow-loopback-callback", Boolean.class, false));
        }
    }

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of())
            .withUserConfiguration(MinimalConfig.class)
            // 显式清掉"配置项"那条路：本用例统一用 OSS_CALLBACK_URL 别名来驱动，
            // 避免"两条来源都有值时到底谁生效"这种与本用例无关的变量
            .withPropertyValues("hy.oss.upload.callback-url=");

    @Test
    @DisplayName("M5_oss_callback_url_required_at_startup（红）：不设且不开降级 → 启动失败，且信息里写清怎么修")
    void failFast_when_callback_url_missing_and_no_explicit_allow() {
        runner.run(context -> {
            assertThat(context)
                    .as("**不设 OSS_CALLBACK_URL 且未开降级开关时必须启动失败** —— "
                            + "这正是本次要堵的洞：以前只有一条没人看的 WARN，"
                            + "结果是【上传成功、界面正常、但图永远不出现】")
                    .hasFailed();
            assertThat(context.getStartupFailure())
                    .as("失败原因应当是明确的配置错误（IllegalStateException），"
                            + "而不是某个 NPE 之类的间接症状")
                    .hasRootCauseInstanceOf(IllegalStateException.class);
            String message = String.valueOf(context.getStartupFailure());
            assertThat(message)
                    .as("错误信息必须写明**怎么修**（给变量名），否则读日志的人只能去翻文档。实际：%s", message)
                    .contains("OSS_CALLBACK_URL")
                    .contains("allow-loopback-callback");
        });
    }

    @Test
    @DisplayName("M5_oss_callback_url_required_at_startup（绿）：设了 OSS_CALLBACK_URL → 启动成功")
    void startsWhenCallbackUrlConfigured() {
        runner.withPropertyValues("OSS_CALLBACK_URL=" + PUBLIC_CALLBACK)
                .run(context -> {
                    assertThat(context)
                            .as("配了公网回调地址就必须能启动。失败原因：%s",
                                    context.getStartupFailure())
                            .hasNotFailed();
                    OssCallbackUrlResolver resolver = context.getBean(OssCallbackUrlResolver.class);
                    assertThat(resolver.resolveQuietly(null))
                            .as("配置了显式地址时，解析结果应当就是它（不再按请求推导）")
                            .isEqualTo(PUBLIC_CALLBACK);
                });
    }

    @Test
    @DisplayName("降级是**显式**的：不设 + 开 allow-loopback-callback → 启动成功")
    void startsWhenLoopbackExplicitlyAllowed() {
        runner.withPropertyValues("hy.oss.upload.allow-loopback-callback=true")
                .run(context -> {
                    assertThat(context)
                            .as("测试/CI 没有公网入口，显式开降级后必须能启动（否则会把测试与 CI 一起带崩）。"
                                    + "失败原因：%s", context.getStartupFailure())
                            .hasNotFailed();
                    assertThat(context.getBean(OssCallbackUrlResolver.class))
                            .as("降级只是关掉启动门，Bean 本身照常可用").isNotNull();
                });
    }

    @Test
    @DisplayName("配了**环回**地址且未开降级 → 也必须失败（显式写错同样要拦住）")
    void failFast_whenLoopbackConfiguredWithoutExplicitAllow() {
        runner.withPropertyValues("OSS_CALLBACK_URL=http://127.0.0.1:8080/api/oss/callback")
                .run(context -> {
                    assertThat(context)
                            .as("把环回地址写进配置、又没开降级开关时必须失败 —— "
                                    + "'本机地址被写进配置'正是当初那次事故的形态，"
                                    + "只拦'没配'而放过'配错了'等于只堵一半")
                            .hasFailed();
                    assertThat(String.valueOf(context.getStartupFailure()))
                            .as("错误信息要指出问题在'环回地址'上，否则读日志的人会以为是自己漏配了")
                            .contains("环回");
                });
    }

    @Test
    @DisplayName("配了环回地址 + 显式开降级 → 允许启动（降级对两种情形都成立）")
    void allowsLoopbackWhenExplicitlyEnabled() {
        runner.withPropertyValues(
                        "OSS_CALLBACK_URL=http://127.0.0.1:8080/api/oss/callback",
                        "hy.oss.upload.allow-loopback-callback=true")
                .run(context -> assertThat(context)
                        .as("显式降级之后，环回地址也允许（测试/CI 的真实形态）。失败原因：%s",
                                context.getStartupFailure())
                        .hasNotFailed());
    }
}
