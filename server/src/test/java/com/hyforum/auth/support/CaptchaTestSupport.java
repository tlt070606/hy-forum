package com.hyforum.auth.support;

import com.hyforum.auth.captcha.CaptchaService;
import com.hyforum.common.redis.RateLimiter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * M1 测试专用的小工具（归纳那些每个用例都要做一次的动作）。
 *
 * <p>放在 {@code com.hyforum.auth.support} 而不是共享的 {@code com.hyforum.support}：
 * 后者归「W1 测试基建」任务所有（docs/agents/工作计划.md §2），本任务不得写入。
 * 本类只被 M1 的用例使用，不构成第二套共享基建。</p>
 *
 * <p><b>它为什么不"作弊"</b>：图形验证码的答案按契约存在 Redis 的
 * {@code hy:captcha:{uuid}} 键里（技术方案 §6.2 / §7）。自动化测试无法识别图片，
 * 因此必须能读到答案。这里刻意<b>通过 {@link CaptchaService} 自己的入口</b>去读
 * （而不是在测试里另拼一遍键名），这样测的是"实现真正写入并读出的那个值"；
 * 若实现把键名或编码改了，这里会立刻失败，而不是测试自己跟自己一致。</p>
 */
@Component
public class CaptchaTestSupport {

    /**
     * Redis 键前缀。刻意与实现（{@code CaptchaService} / {@code RateLimiter}）
     * 中声明的常量同名同值：这两处 string 是契约（技术方案 §7 的 Key 模式表）里的字面量，
     * 测试需要独立地写一遍，才能在实现改错前缀时报错 —— 若从实现里 import 过来，
     * 前缀被改错时测试会跟着一起错，等于没有断言。
     */
    private static final String CAPTCHA_KEY_PREFIX = "hy:captcha:";

    private static final String RATE_LIMIT_KEY_PREFIX = "hy:rl:";

    @Autowired
    private CaptchaService captchaService;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private RateLimiter rateLimiter;

    /** 取一张新验证码，返回 uuid 与其正确答案（答案即实现写入 Redis 的值）。 */
    public Issued issue() {
        CaptchaService.CaptchaChallenge challenge = captchaService.generate();
        String answer = captchaService.peekCode(challenge.uuid());
        if (answer == null) {
            throw new IllegalStateException("生成验证码后 Redis 里没有答案，说明 hy:captcha:{uuid} 未被写入："
                    + challenge.uuid());
        }
        return new Issued(challenge.uuid(), answer, challenge.base64Image());
    }

    /** Redis 里该验证码键的剩余 TTL（秒）。 */
    public long ttlSeconds(String uuid) {
        Long ttl = stringRedisTemplate.getExpire(captchaService.redisKey(uuid));
        return ttl == null ? -2 : ttl;
    }

    public boolean hasCaptchaKey(String uuid) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(captchaService.redisKey(uuid)));
    }

    /**
     * 清空< b>本任务产生的</b> Redis 状态：验证码与限流计数。
     *
     * <p>只按 {@code hy:captcha:*} / {@code hy:rl:*} 前缀删除，<b>不执行 FLUSHDB</b> ——
     * 这台 Redis 是 VM 里的共享实例（docs/agents/README.md §7.2），
     * 里面还有其他容器栈的数据，全库清空是不可接受的操作。</p>
     */
    public void clearAuthRedisState() {
        deleteByPrefix(CAPTCHA_KEY_PREFIX + "*");
        deleteByPrefix(RATE_LIMIT_KEY_PREFIX + "*");
    }

    /** 把某个动作的限流计数清零（让"打满限流"的用例不污染后续用例）。 */
    public void resetRateLimit(String action, String identity) {
        rateLimiter.reset(action, identity);
    }

    private void deleteByPrefix(String pattern) {
        Set<String> keys = stringRedisTemplate.keys(pattern);
        if (keys != null && !keys.isEmpty()) {
            stringRedisTemplate.delete(keys);
        }
    }

    /** 一张验证码：uuid + 正确答案 + 图片。 */
    public record Issued(String uuid, String answer, String base64Image) {
    }
}
