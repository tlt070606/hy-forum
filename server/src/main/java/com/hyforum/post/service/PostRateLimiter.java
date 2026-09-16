package com.hyforum.post.service;

import com.hyforum.common.api.ErrorCode;
import com.hyforum.common.exception.BizException;
import com.hyforum.common.redis.RateLimiter;
import com.hyforum.domain.user.entity.User;
import com.hyforum.post.config.PostProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 发帖频率限制（docs/技术方案.md §8.7）—— 交接事项 <b>H1</b> 的落点。
 *
 * <pre>
 * 发帖：新注册用户 24 小时内 ≤ 3 帖；普通用户 ≤ 10 帖/小时
 * </pre>
 *
 * <h2>「新注册用户」怎么判定</h2>
 * <p>判据是 {@code user.created_at} 距今是否在 {@code newUserWindowHours}（契约 24 小时）之内。
 * 契约原文只有"新注册用户 24 小时内"这一句，实现必须自己把它落到一个可判定的事实上 ——
 * 用注册时间而不是"发帖数"或"等级"，是因为前者是库里已有的事实，后者会引入新的状态。</p>
 *
 * <p><b>两条线是二选一，不是叠加</b>：新用户走 3 帖/24h，老用户走 10 帖/小时。
 * 若写成"新用户同时受 3/24h 与 10/1h 限制"，行为上等价于 3/24h（更严的那条总是先触发），
 * 但会让错误提示与真实原因对不上（用户看到"1 小时内超过 10 帖"，实际上他才发了 4 帖）。</p>
 *
 * <h2>为什么复用 M1 的 {@link RateLimiter} 而不是自己写 INCR</h2>
 * <p>§8.7 要求"基于 Redis INCR + EXPIRE 实现"，M1 的实现已经把两处最易错的细节处理掉了：
 * ① TTL 只在首次计数时设置（否则持续打请求会让计数器永不过期，等于把用户永久锁死）；
 * ② TTL 异常（-1）时补设过期，避免限流窗口永久化。
 * 每个模块各写一遍，迟早会有一处漏掉这两条。</p>
 *
 * <h2>调用位置与顺序（重要）</h2>
 * <p>限流在 {@code PostService.create} 的<b>业务校验之前</b>执行。取舍：
 * 请求若因"版块不存在"这类业务原因被拒，配额照样被消耗。
 * 反过来（先校验后限流）会留出一条刷量路径：构造必然失败的请求就能无限刷发帖接口。
 * 两种取舍都有代价，这里选了"对滥用更严"的那一边，并把结论写在这里备查。</p>
 */
@Component
public class PostRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(PostRateLimiter.class);

    /** 限流动作标识：新注册用户（24 小时窗口）。 */
    static final String ACTION_NEW_USER = "post-new-user-24h";

    /** 限流动作标识：普通用户（1 小时窗口）。 */
    static final String ACTION_HOURLY = "post-hourly";

    private final RateLimiter rateLimiter;
    private final PostProperties properties;

    public PostRateLimiter(RateLimiter rateLimiter, PostProperties properties) {
        this.rateLimiter = rateLimiter;
        this.properties = properties;
    }

    /**
     * 计数并判定；超限直接抛 {@code 2002 发帖过于频繁}。
     *
     * @param author 发帖人（需要 {@code created_at} 来判定是否为"新注册用户"）
     * @throws BizException {@link ErrorCode#POST_TOO_FREQUENT}
     */
    public void checkAndCount(User author) {
        boolean newUser = isNewUser(author);
        String action = newUser ? ACTION_NEW_USER : ACTION_HOURLY;
        int limit = newUser ? properties.newUserPostLimit() : properties.postPerHourLimit();
        Duration window = newUser
                ? Duration.ofHours(properties.newUserWindowHours())
                : Duration.ofHours(1);

        RateLimiter.Decision decision =
                rateLimiter.check(action, String.valueOf(author.getId()), limit, window);
        if (!decision.allowed()) {
            log.warn("发帖限流命中：userId={} newUser={} 限额={} 当前计数={}",
                    author.getId(), newUser, limit, decision.count());
            // 提示语带上重试秒数（§8.7 要求给出友好提示；M1 的做法与此一致，
            // 正式的 Retry-After 响应头由 M5 的统一限流切面补齐，见交接事项 H2）
            throw new BizException(ErrorCode.POST_TOO_FREQUENT,
                    ErrorCode.POST_TOO_FREQUENT.message() + "，请 " + decision.retryAfter() + " 秒后重试");
        }
    }

    /** 注册时间在观察窗口之内即视为"新注册用户"；注册时间为空（历史脏数据）按老用户处理。 */
    private boolean isNewUser(User author) {
        LocalDateTime createdAt = author.getCreatedAt();
        if (createdAt == null) {
            return false;
        }
        return createdAt.isAfter(LocalDateTime.now().minusHours(properties.newUserWindowHours()));
    }
}
