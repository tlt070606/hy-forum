package com.hyforum.auth.captcha;

import com.hyforum.common.config.CaptchaProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;

/**
 * 图形验证码服务（docs/技术方案.md §6.2 / §7）。
 *
 * <p>契约要求（逐条落地）：</p>
 * <ul>
 *   <li>{@code GET /api/auth/captcha} 返回 {@code {uuid, base64Image}}；</li>
 *   <li>答案存 Redis 键 {@code hy:captcha:{uuid}}；</li>
 *   <li><b>TTL = 300s</b>（验收项 {@code M1_captcha_ttl_is_300s} 直接断言）；</li>
 *   <li>校验失败返回错误码 1003（验收项 {@code M1_captcha_wrong_returns_1003}）。</li>
 * </ul>
 *
 * <p><b>为什么校验成功后立即删除</b>：验证码是一次性凭据。若不删除，攻击者拿到一个
 * 通过验证的 uuid 就可以无限次注册（"验证码重放"）。删除后同一 uuid 第二次使用必然
 * 返回 1003。</p>
 *
 * <p><b>为什么校验时忽略大小写</b>：图片验证码的字符是大写展示的，用户可能输入小写。
 * 若区分大小写，会显著提高正常用户的失败率而几乎不增加攻击成本
 * （字符集只有 32 个符号，大小写不构成安全边界）。</p>
 */
@Service
public class CaptchaService {

    /** Redis 键前缀，与技术方案 §7 的 {@code hy:captcha:{uuid}} 一致。 */
    private static final String KEY_PREFIX = "hy:captcha:";

    /**
     * 字符集：刻意剔除易混淆字符 {@code 0 O 1 I L}。
     * 中文用户区分 0/O、1/l/I 的成本很高，而攻击者本来就能拿到全部字符集，
     * 剔除它们只提升可用性、不降低安全性。
     */
    private static final char[] ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ".toCharArray();

    private final StringRedisTemplate redis;
    private final CaptchaProperties properties;
    private final SecureRandom random = new SecureRandom();

    public CaptchaService(StringRedisTemplate redis, CaptchaProperties properties) {
        this.redis = redis;
        this.properties = properties;
    }

    /**
     * 生成一张新验证码：答案写 Redis，图片以 Base64(PNG) 返回。
     *
     * @return 挑战（uuid + 图片 + 有效期），对应契约里的 {@code {uuid, base64Image}}
     */
    public CaptchaChallenge generate() {
        String uuid = UUID.randomUUID().toString().replace("-", "");
        String code = randomCode();
        redis.opsForValue().set(KEY_PREFIX + uuid, code, Duration.ofSeconds(properties.ttlSeconds()));
        return new CaptchaChallenge(uuid, renderBase64(code), properties.ttlSeconds());
    }

    /**
     * 校验并消费验证码（一次性）。
     *
     * @param uuid 验证码标识
     * @param input 用户输入
     * @return true 表示校验通过（此时 Redis 键已被删除）
     */
    public boolean verifyAndConsume(String uuid, String input) {
        if (uuid == null || uuid.isBlank() || input == null || input.isBlank()) {
            return false;
        }
        String expected = redis.opsForValue().get(KEY_PREFIX + uuid);
        // 先删除再比较：无论成功失败都是一次性，避免"错误输入也能反复重试 + 高频打 Redis"
        redis.delete(KEY_PREFIX + uuid);
        if (expected == null) {
            return false;
        }
        return expected.equalsIgnoreCase(input.trim());
    }

    /**
     * 读取某个 uuid 当前的答案，<b>不移除</b>。
     *
     * <p>用途说明：这是给测试用的观测口 —— 契约把答案存放位置写成了 Redis 键
     * {@code hy:captcha:{uuid}}，验收项 {@code M1_captcha_ttl_is_300s} 要断言这个键的 TTL。
     * 测试必须通过"被测实现自己用的那个入口"去看，而不是另拼一个键名（那样测的是测试拼的
     * 字符串，不是实现的行为）。生产代码在任何鉴权路径上都<b>不会</b>调用本方法。</p>
     *
     * @return 答案（大写）；不存在或已过期返回 null
     */
    public String peekCode(String uuid) {
        return redis.opsForValue().get(KEY_PREFIX + uuid);
    }

    /** Redis 键名，供测试与排障使用（与 {@link #peekCode} 同源，避免键名两处拼装）。 */
    public String redisKey(String uuid) {
        return KEY_PREFIX + uuid;
    }

    private String randomCode() {
        StringBuilder builder = new StringBuilder(properties.length());
        for (int i = 0; i < properties.length(); i++) {
            builder.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return builder.toString();
    }

    /**
     * 绘制验证码图片并返回 Base64（不带 data URI 前缀，前端自行拼 {@code data:image/png;base64,}）。
     *
     * <p>干扰手段：随机颜色、随机旋转、少量噪点与干扰线。目的不是做成"不可破解"，
     * 而是把自动化脚本的成本抬高到"不如直接人工过"的水平 —— 配合 §8.7 的限流共同起效。</p>
     */
    private String renderBase64(String code) {
        int width = properties.width();
        int height = properties.height();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // 背景：浅色（保证深色字符对比度足够）
            g.setColor(new Color(245, 247, 250));
            g.fillRect(0, 0, width, height);

            // 干扰线：半透明，避免盖住字符
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.35f));
            g.setStroke(new BasicStroke(1.2f));
            for (int i = 0; i < 6; i++) {
                g.setColor(randomColor(120, 200));
                g.drawLine(random.nextInt(width), random.nextInt(height),
                        random.nextInt(width), random.nextInt(height));
            }
            g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1.0f));

            // 字符：逐字随机旋转
            int fontSize = (int) (height * 0.7);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, fontSize));
            int step = width / (code.length() + 1);
            for (int i = 0; i < code.length(); i++) {
                AffineTransform saved = g.getTransform();
                double angle = (random.nextDouble() - 0.5) * 0.6;
                int x = step * (i + 1) - fontSize / 2;
                int y = height - (height - fontSize) / 2 - 4;
                g.rotate(angle, x + fontSize / 2.0, y - fontSize / 2.0);
                g.setColor(randomColor(20, 110));
                g.drawString(String.valueOf(code.charAt(i)), x, y);
                g.setTransform(saved);
            }

            // 噪点
            for (int i = 0; i < 60; i++) {
                g.setColor(randomColor(120, 210));
                g.fillRect(random.nextInt(width), random.nextInt(height), 1, 1);
            }
        } finally {
            g.dispose();
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException ex) {
            // PNG 写入内存流不会失败；真失败说明 JDK 环境异常，必须显式暴露而不是返回空图
            throw new UncheckedIOException("生成验证码图片失败", ex);
        }
    }

    private Color randomColor(int min, int max) {
        // 钳制到合法区间：调用方传错范围时给出可用的颜色，而不是抛 IllegalArgumentException
        // （验证码生成失败会让整个注册入口不可用，不值得为一个装饰性参数冒这个风险）
        int lower = Math.max(0, Math.min(min, max));
        int upper = Math.min(255, Math.max(min, max));
        int range = Math.max(1, upper - lower);
        return new Color(lower + random.nextInt(range), lower + random.nextInt(range), lower + random.nextInt(range));
    }

    /**
     * 验证码挑战。
     *
     * @param uuid        验证码标识（提交注册时回传）
     * @param base64Image Base64 编码的 PNG
     * @param ttlSeconds  有效期（契约值 300）
     */
    public record CaptchaChallenge(String uuid, String base64Image, int ttlSeconds) {
    }
}
