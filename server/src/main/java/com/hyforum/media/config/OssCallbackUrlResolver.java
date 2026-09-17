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
 * <p>⚠️ 刻意<b>不</b>做成"启动即失败"：那会带崩测试与 CI（它们本来就用环回地址起应用）。
 * 要的是<b>可见</b>，不是拦死 —— 这是 §12.5 的明确要求。</p>
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

    /** 请求级 WARN 只打一次（否则每个签名请求一行，等于没有信号）。 */
    private final AtomicBoolean loopbackWarned = new AtomicBoolean(false);

    /** 请求级 WARN 的累计次数（只增），供测试与排障观察；见 {@link #resetLoopbackWarningState()}。 */
    private final AtomicLong loopbackWarningCount = new AtomicLong();

    public OssCallbackUrlResolver(OssUploadProperties uploadProperties,
                                  @Value("${OSS_CALLBACK_URL:}") String callbackUrlFromEnv) {
        this.uploadProperties = uploadProperties;
        this.callbackUrlFromEnv = callbackUrlFromEnv;
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
