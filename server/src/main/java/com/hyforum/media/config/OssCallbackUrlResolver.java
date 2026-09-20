package com.hyforum.media.config;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 回调地址解析 + **"回调地址不可达"的可见性**（任务 C，§12.5）。
 *
 * <h2>要解决的现象</h2>
 * <p>回调地址默认<b>从请求推导</b>。本机开发经 Vite 代理调用时，它会推出
 * {@code http://127.0.0.1:8080/api/oss/callback} —— 而 <b>OSS 在公网，永远够不到环回地址</b>。
 * 现象是"<b>上传成功但没有图</b>"，而且<b>日志里一个错都没有</b>：
 * OSS 那边回调失败、我们这边什么都没发生，两边都"正常"。
 * 这类静默失败是所有故障里最难查的一种，所以这里把它变成<b>一条明确的 WARN</b>。</p>
 *
 * <p>⚠️ <b>2026-09-20 升级：从"启动提醒一次"改为"不设就启动失败"</b>
 * （需求方裁决；M5 任务书 §9）。原设计刻意不拦死，理由是"会带崩测试与 CI"——
 * 那个顾虑是真的，所以改用<b>显式降级开关</b>解决，而不是继续容忍静默失败：
 * 不设 {@code OSS_CALLBACK_URL} 且未开降级 → <b>启动即失败</b>；
 * {@code hy.oss.upload.allow-loopback-callback=true} → 允许环回（测试/CI 走这条）。
 * 理由与取舍写在 {@link #assertCallbackUrlUsableOrFailFast()}。</p>
 *
 * <h2>解析优先级</h2>
 * <ol>
 *   <li>{@code hy.oss.upload.callback-url}（配置项，显式指定优先）；</li>
 *   <li>环境变量 {@code OSS_CALLBACK_URL}（等价别名，给运维一个直白的入口）；</li>
 *   <li>{@code X-Forwarded-Proto}/{@code X-Forwarded-Host}（经过 nginx/隧道时才是公网地址）；</li>
 *   <li>请求自身的 scheme + host + port。</li>
 * </ol>
 */
@Component
public class OssCallbackUrlResolver {

    private static final Logger log = LoggerFactory.getLogger(OssCallbackUrlResolver.class);

    /** 回调端点路径（与 Controller 的映射一致；只在这里写一次）。 */
    public static final String CALLBACK_PATH = "/api/oss/callback";

    /** 请求级 WARN 里的一句固定标识（测试用它断言"这条 WARN 真的打了"）。 */
    public static final String LOOPBACK_WARN_MARKER = "从请求推导出的回调地址是环回地址";

    private final OssUploadProperties uploadProperties;

    /**
     * {@code OSS_CALLBACK_URL} 别名。
     *
     * <p>为什么不只留配置项：那段 WARN 要告诉开发者"怎么办"，而它给出的办法必须<b>真的能用</b>；
     * 一个名字写错的建议等于又一条"绿色的假话"。§12.5 点名的就是这个名字，于是这里让它成立。</p>
     */
    private final String callbackUrlFromEnv;

    /**
     * 是否<b>显式允许</b>环回回调地址（{@code hy.oss.upload.allow-loopback-callback}，默认 {@code false}）。
     *
     * <p>见 {@link #assertCallbackUrlUsableOrFailFast()} 的说明：这是"显式降级"的开关，
     * 用于测试与 CI（它们本来就用环回地址起应用，没有公网入口）。</p>
     */
    private final boolean allowLoopbackCallback;

    /** 请求级 WARN 只打一次（否则每个签名请求一行，等于没有信号）。 */
    private final AtomicBoolean loopbackWarned = new AtomicBoolean(false);

    /** 请求级 WARN 的累计次数（只增），供测试与排障观察；见 {@link #resetLoopbackWarningState()}。 */
    private final AtomicLong loopbackWarningCount = new AtomicLong();

    public OssCallbackUrlResolver(OssUploadProperties uploadProperties,
                                  @Value("${OSS_CALLBACK_URL:}") String callbackUrlFromEnv,
                                  @Value("${hy.oss.upload.allow-loopback-callback:false}")
                                  boolean allowLoopbackCallback) {
        this.uploadProperties = uploadProperties;
        this.callbackUrlFromEnv = callbackUrlFromEnv;
        this.allowLoopbackCallback = allowLoopbackCallback;
        // ★ fail-fast 在**构造期**执行（见方法注释里为什么不是 @PostConstruct）
        assertCallbackUrlUsableOrFailFast();
    }


    /**
     * 回调地址不可用 → <b>启动即失败</b>（需求方 2026-09-20 裁决；M5 任务书 §9）。
     *
     * <h2>为什么从"一条 WARN"升级成"启动失败"</h2>
     * <p>L1 亲身踩过：起后端时漏设 {@code OSS_CALLBACK_URL} → 回调地址按请求推导出<b>环回地址</b> →
     * <b>OSS 在公网永远够不到</b> → 现象是「<b>上传成功、界面正常、但图永远不出现</b>」，
     * 而当时只有<b>一条 WARN</b>。**没人会去看 WARN** —— 于是这个配置错误一直活到用户发现图不见了。
     * 因此改成与凭据（{@code @NotBlank}）同一口径：<b>不设就起不来</b>。</p>
     *
     * <h2>为什么可以"显式降级"</h2>
     * <p>测试与 CI <b>没有公网入口</b>，它们本来就用环回地址起应用。
     * 若一刀切地拦死，会把测试与 CI 一起带崩 —— 那不是"更安全"，是"把闸门焊死"。
     * 因此保留一条<b>必须显式声明</b>的降级路径：
     * {@code hy.oss.upload.allow-loopback-callback=true}。
     * <b>默认 false</b>：默认路径安全，降级要写出来（写出来的东西才会被评审看见）。</p>
     *
     * <h2>为什么在构造器里做、而不是 {@code @PostConstruct}</h2>
     * <p>失败要发生在<b>依赖注入阶段</b>：这样应用上下文直接构建失败、进程退出，
     * 而不是"Bean 建好了、启动到一半才炸"。用 {@link IllegalStateException} 而不是
     * {@code BizException}：这不是一次"可预期的业务失败"，而是<b>配置错误</b>，
     * 它不该被全局异常处理器翻译成一个 HTTP 响应体（那时根本没有请求）。</p>
     *
     * <p>错误信息里必须写清<b>怎么修</b>：只说"配置缺失"会让人去翻文档，
     * 而把两个变量名与"本机开发可以开降级开关"写进去，读日志的人当场就能改。</p>
     */
    private void assertCallbackUrlUsableOrFailFast() {
        String explicit = firstNonBlank(uploadProperties.callbackUrl(), callbackUrlFromEnv);
        if (explicit == null) {
            if (allowLoopbackCallback) {
                // 显式降级：测试/CI 走这条。仍然留一条 INFO，便于事后确认"这次是降级跑的"
                log.info("未配置 OSS 回调地址，但已显式允许环回回调"
                        + "（hy.oss.upload.allow-loopback-callback=true）—— 仅适用于测试/CI，"
                        + "生产环境必须设置 OSS_CALLBACK_URL，否则 OSS 无法回调（现象：上传成功但图不出现）");
                return;
            }
            throw new IllegalStateException(
                    "未配置 OSS 回调地址，拒绝启动。"
                            + "原因：回调地址会按请求推导，本机/容器里通常推出环回地址（如 127.0.0.1），"
                            + "而 OSS 在公网够不到它 —— 现象是【上传成功、界面正常、但图永远不出现】，"
                            + "且日志里没有错误。请设置环境变量 OSS_CALLBACK_URL（公网可访问的地址）；"
                            + "本机开发/测试若不需要真实回调，可显式设置 "
                            + "hy.oss.upload.allow-loopback-callback=true 来降级。");
        }
        if (isLoopback(explicit) && !allowLoopbackCallback) {
            throw new IllegalStateException(
                    "配置的 OSS 回调地址是环回地址 [" + explicit + "]，拒绝启动。"
                            + "OSS 在公网访问不到环回地址 —— 现象是【上传成功、界面正常、但图永远不出现】。"
                            + "请改为公网入口；本机开发/测试若不需要真实回调，可显式设置 "
                            + "hy.oss.upload.allow-loopback-callback=true 来降级。");
        }
    }

    /**
     * 解析本次请求应当下发给前端的回调地址。
     *
     * @param request 当前请求（可为 null，例如启动期探活）
     * @return 完整回调 URL；环回地址时同时打一条 WARN（只打一次）
     */
    public String resolve(HttpServletRequest request) {
        String resolved = resolveQuietly(request);
        if (isLoopback(resolved)) {
            warnLoopbackOnce(resolved);
        }
        return resolved;
    }

    /** 解析但不打 WARN（启动期用它判断，免得重复告警）。 */
    public String resolveQuietly(HttpServletRequest request) {
        String explicit = firstNonBlank(uploadProperties.callbackUrl(), callbackUrlFromEnv);
        if (explicit != null) {
            return explicit;
        }
        if (request == null) {
            return "";
        }
        String scheme = firstNonBlank(request.getHeader("X-Forwarded-Proto"), request.getScheme());
        String host = firstNonBlank(request.getHeader("X-Forwarded-Host"), request.getServerName());
        int port = request.getServerPort();
        boolean defaultPort = ("http".equals(scheme) && port == 80) || ("https".equals(scheme) && port == 443);
        return scheme + "://" + host + (defaultPort ? "" : ":" + port) + CALLBACK_PATH;
    }

    /**
     * 启动时提醒一次（§12.5：启动 + 首次签名各一条）。
     *
     * <p>启动期没有请求，所以能判断的只有两种情况：显式配置了环回地址（明确错），
     * 或<b>根本没配</b>（那么它就会按请求推导，本机开发极可能推出环回地址）。</p>
     */
    @EventListener(ApplicationReadyEvent.class)
    public void warnOnStartupIfCallbackMayBeUnreachable() {
        String explicit = firstNonBlank(uploadProperties.callbackUrl(), callbackUrlFromEnv);
        if (explicit == null) {
            log.warn("未显式配置 OSS 回调地址，将按请求推导。若请求来自环回地址（本机开发很常见），"
                    + "OSS 将无法回调：现象是【上传成功但没有图】且日志无错。"
                    + "本机要收真实回调，请把它指向公网入口（环境变量 OSS_CALLBACK_URL）。"
                    + "（这不影响测试与 CI —— 它们本来就用环回地址起应用）");
            return;
        }
        if (isLoopback(explicit)) {
            log.warn("配置的 OSS 回调地址是环回地址 [{}]，OSS 在公网访问不到它："
                    + "现象是【上传成功但没有图】且日志无错。请改为公网入口。", explicit);
        }
    }

    /** 请求级 WARN（只打一次）。 */
    private void warnLoopbackOnce(String resolvedUrl) {
        if (loopbackWarned.compareAndSet(false, true)) {
            log.warn("{} [{}]：OSS 在公网，访问不到环回地址，真实回调会失败 —— "
                    + "现象是【上传成功但没有图】且日志无错。本机开发请把回调地址指向公网入口"
                    + "（环境变量 OSS_CALLBACK_URL，或配置 hy.oss.upload.callback-url）。",
                    LOOPBACK_WARN_MARKER, resolvedUrl);
            loopbackWarningCount.incrementAndGet();
        }
    }

    /** 环回地址判定（§12.5：{@code 127.0.0.1} / {@code localhost} / {@code ::1}）。 */
    public static boolean isLoopback(String url) {
        if (url == null || url.isBlank()) {
            return false;
        }
        try {
            String host = URI.create(url.trim()).getHost();
            if (host == null) {
                return false;
            }
            String normalized = host.toLowerCase(Locale.ROOT);
            // 0.0.0.0 也归入"OSS 到不了"：它不是可路由的公网地址
            return normalized.equals("localhost")
                    || normalized.equals("::1")
                    || normalized.equals("[::1]")
                    || normalized.equals("0.0.0.0")
                    || normalized.startsWith("127.");
        } catch (RuntimeException ex) {
            // 解析不了就不判定（宁可少告警，也不要因为一个畸形配置把启动日志刷成告警）
            return false;
        }
    }

    /** 请求级 WARN 累计次数（测试与排障用；只增）。 */
    public long loopbackWarningCount() {
        return loopbackWarningCount.get();
    }

    /**
     * 重置"只告警一次"的状态（<b>供测试与运维排障使用</b>，与 M1 的 {@code RateLimiter.reset}
     * 同一性质）：同一个 JVM 里只打一次的告警，若不能重置，就没法被用例稳定断言。
     */
    public void resetLoopbackWarningState() {
        loopbackWarned.set(false);
        loopbackWarningCount.set(0);
    }

    private static String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first.trim();
        }
        return (second != null && !second.isBlank()) ? second.trim() : null;
    }
}
