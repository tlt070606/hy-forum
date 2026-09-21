package com.hyforum.common.oss;

import org.springframework.stereotype.Component;

/**
 * <b>头像 URL 的唯一装配入口</b>（CR-Q，2026-09-20 L1 裁决）。
 *
 * <h2>为什么要有这个类：同一个 bug 形态出现了第三次</h2>
 * <p>"头像"这件事有三个消费者，而它们被逐个发现、每次漏一个：</p>
 * <table>
 *   <caption>三个消费者</caption>
 *   <tr><th>#</th><th>消费者</th><th>状态</th></tr>
 *   <tr><td>1</td><td>签名下发（{@code /api/oss/signature?target=avatar}）</td>
 *       <td>§14 做了</td></tr>
 *   <tr><td>2</td><td>写入校验（{@code PUT /api/user/profile} 的归属校验）</td>
 *       <td>§14 做了</td></tr>
 *   <tr><td>3</td><td><b>读取时签名</b>（§12 读时签名）</td>
 *       <td><b>§14 漏了 → CR-Q</b>：头像不带签名 → 桶私有 → 前端 403</td></tr>
 * </table>
 * <p>前两次是"回调服务没接受 avatar 目录"（CallbackFailed），这次是"读时签名没覆盖头像"。
 * <b>三次都是同一个形态：改了一件事，却没把所有消费者列全。</b></p>
 *
 * <h2>所以这次用"结构"堵，而不是"再补一行"</h2>
 * <p>L1 明确不接受"在三个 VO 里各补一行签名"—— 因为头像出现在<b>帖子作者、评论作者、
 * 通知发送者、关注/粉丝列表、侧栏最新发帖的人</b>……只要有一个装配点漏调，就是第四次。
 * 因此：</p>
 * <ol>
 *   <li><b>本类是头像 URL 的唯一出口</b>：所有装配头像的地方都调
 *       {@link #resolve(com.hyforum.domain.user.entity.User)}；</li>
 *   <li><b>结构上禁止绕过</b>：ArchUnit 规则 {@code ARCH_avatar_url_must_go_through_resolver}
 *       禁止 {@code domain} 与 {@code common.oss} 之外的任何类调用
 *       {@code User.getAvatarUrl()} —— 新增装配点若忘了走本类，<b>编译能过但架构门会红</b>；</li>
 *   <li>新增一处时的"凭什么不会再漏"：<b>因为绕不过去</b>。要么调本类，要么架构测试红。
 *       这比"我记得"可靠 —— 本任务书已经证明"我记得"失败过三次。</li>
 * </ol>
 *
 * <h2>边界（L1 要求写成断言的三条）</h2>
 * <ul>
 *   <li>{@code avatarUrl} 为 {@code null}/空白 → <b>保持 {@code null}</b>
 *       （绝不"签名一个空串"：那会让前端拿到一个非空的垃圾串，回落默认头像的逻辑就失效了）；</li>
 *   <li><b>不属于本项目 OSS 前缀的 URL（历史数据、外链）→ 原样返回，不签名</b>。
 *       签名它没有意义，而且会让前端拿到一个必然 403 的链接 —— 原样返回至少让
 *       "这是一条老数据"这件事保持可见，而不是被一个假签名掩盖；</li>
 *   <li>不属于<b>该用户自己</b>目录的 URL（例如别人的 {@code avatar/{other}/}）
 *       同样原样返回：它本来就不该出现在这个字段里（写入侧已挡），
 *       读侧不替它签名，免得把一个越权数据"修好"成看起来正常的样子。</li>
 * </ul>
 *
 * <p><b>为什么放在 {@code common.oss}</b>：{@code post}、{@code interaction}、{@code notify}、
 * {@code user} 四个包都要用它，而它们之间禁止互相依赖（铁律 3）。
 * 与 {@link OssReadUrlSigner}、{@code common.audit.SensitiveTextChecker} 同一处置。</p>
 */
@Component
public class AvatarUrlResolver {

    private final OssProperties ossProperties;
    private final AvatarUrlSigner avatarUrlSigner;

    public AvatarUrlResolver(OssProperties ossProperties, AvatarUrlSigner avatarUrlSigner) {
        this.ossProperties = ossProperties;
        this.avatarUrlSigner = avatarUrlSigner;
    }

    /**
     * 装配一个用户的头像 URL（<b>所有 VO 都必须走这里</b>）。
     *
     * @param user 用户实体；{@code null}（已注销/查不到）或头像为空时返回 {@code null}
     * @return 可渲染的头像 URL（本站对象带读时签名）；无头像时 {@code null}
     */
    public String resolve(com.hyforum.domain.user.entity.User user) {
        if (user == null) {
            return null;
        }
        return resolve(user.getId(), user.getAvatarUrl());
    }

    /**
     * 同上，但直接给 id 与裸 URL（供已经拆开字段的调用方用，例如
     * {@code ProfileService} 手里有"刚写入的值"而不是实体）。
     *
     * @param userId 头像所属用户 id
     * @param rawUrl 库里存的裸 URL
     */
    public String resolve(Long userId, String rawUrl) {
        if (userId == null || rawUrl == null) {
            return null;
        }
        String trimmed = rawUrl.trim();
        if (trimmed.isEmpty()) {
            // 空白按"没有头像"处理，而不是"签名一个空串"（见类注释的边界第一条）
            return null;
        }
        if (!belongsToUserDirectory(userId, trimmed)) {
            // 外链 / 历史数据 / 别人的目录：原样返回，不签名
            return trimmed;
        }
        return avatarUrlSigner.sign(userId, trimmed);
    }

    /**
     * 这个 URL 是否落在 {@code avatar/{userId}/} 前缀内。
     *
     * <p>前缀取自 {@link OssProperties#userAvatarUrlPrefix(long)} ——
     * 与<b>签名下发</b>、<b>写入校验</b>同一来源（§14.2 ②）。
     * 这里不做任何自己的字符串拼接：目录这件事必须只有一个来源。</p>
     */
    private boolean belongsToUserDirectory(long userId, String url) {
        String allowedPrefix = ossProperties.userAvatarUrlPrefix(userId);
        return !allowedPrefix.isEmpty() && url.startsWith(allowedPrefix);
    }
}
