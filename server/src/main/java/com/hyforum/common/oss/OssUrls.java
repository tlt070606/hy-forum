package com.hyforum.common.oss;

import org.springframework.stereotype.Component;

/**
 * <b>对外返回 OSS 地址的唯一入口</b>（CR-Q 扩展，2026-09-20 L1 裁决）。
 *
 * <h2>为什么要有这个类（"把清单变成遍历"之前的"把出口收成一个"）</h2>
 * <p>桶是私有的：库里存的是<b>裸 URL</b>，任何对外返回的 OSS 地址都必须在<b>读时</b>签名，
 * 否则前端拿到 403。而这个"必须在读时签名"的规则在本项目被漏过<b>多次</b>：</p>
 * <ul>
 *   <li>§14：头像整块漏了读时签名 → 现象"帖子图能看、头像不能"（CR-Q）；</li>
 *   <li>CR-Q 扩展：`CollectionItemVO.coverUrl`、`FeedItemVO.coverUrl` 也是裸的 ——
 *       前端是靠<b>肉眼逐个撞</b>出来的。</li>
 * </ul>
 * <p>所以不再"逐处补"，而是：<b>所有装配 OSS 地址的地方只允许调本类</b>。
 * 本类把三件事收在一处：</p>
 * <ol>
 *   <li>签名（{@link #sign(String)}）；</li>
 *   <li>缩略图地址推导 + 签名（{@link #thumbs(java.util.List, int)}）；</li>
 *   <li>头像的归属校验 + 签名（委托 {@link AvatarUrlResolver}，见 {@link #avatar})。</li>
 * </ol>
 *
 * <h2>新增字段时凭什么不会漏</h2>
 * <p>两道：</p>
 * <ol>
 *   <li><b>结构</b>：ArchUnit {@code ARCH_avatar_url_must_go_through_resolver} 禁止绕过头像解析器；
 *       本类的 {@link #sign(String)} 是唯一签名入口；</li>
 *   <li><b>遍历（真正的护栏）</b>：{@code M4_api_responses_have_no_unsigned_oss_url} 会把
 *       <b>所有会返回 OSS 地址的读接口</b>的响应 JSON 遍历一遍，抓出每一个本项目 OSS 域名的 URL
 *       并断言它带 {@code Signature=}。
 *       <b>它不依赖任何人记得清单</b> —— 新增字段若忘了签名，只要那个端点被遍历到，它自己会红。</li>
 * </ol>
 *
 * <h2>边界（与 {@link OssReadUrlSigner} 同一套纪律）</h2>
 * <ul>
 *   <li>{@code null}/空白 → 返回 {@code null}（不"签名一个空串"）；</li>
 *   <li>非本项目 OSS 前缀（历史数据/外链）→ <b>原样返回，不签名</b>；</li>
 *   <li>已经签过的 → 原样返回（不重复叠加参数）；</li>
 *   <li>有效期**不另设**：与帖子图共用 {@code hy.oss.read-url-ttl}（同一个实现）。</li>
 * </ul>
 */
@Component
public class OssUrls {

    /** 卡片上最多给几张缩略图（与既有 {@code CardViewerStateEnricher} 同一口径）。 */
    public static final int CARD_THUMB_LIMIT = 3;

    private final OssReadUrlSigner readUrlSigner;
    private final AvatarUrlResolver avatarUrlResolver;

    public OssUrls(OssReadUrlSigner readUrlSigner, AvatarUrlResolver avatarUrlResolver) {
        this.readUrlSigner = readUrlSigner;
        this.avatarUrlResolver = avatarUrlResolver;
    }

    /**
     * 给一个 OSS 对象地址签名（封面、原图、缩略图……任何"库里存的裸地址"都走这里）。
     *
     * @param rawUrl 库里的裸 URL；{@code null}/空白/非本站/已签 → 原样（空白归一为 {@code null}）
     */
    public String sign(String rawUrl) {
        if (rawUrl == null) {
            return null;
        }
        String trimmed = rawUrl.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        return readUrlSigner.sign(trimmed);
    }

    /**
     * 由一组原图地址推导缩略图并**逐个签名**（列表卡片用）。
     *
     * <p>推导用 {@link OssThumbnailUrls#derive}、签名用同一个读时签名实现 ——
     * 不在这里自己拼 URL（"URL 拼接"与"签名"都只允许有一处实现）。</p>
     *
     * @param originalUrls 原图裸地址，可为 {@code null}
     * @param limit        最多取几张；{@code <= 0} 时用 {@link #CARD_THUMB_LIMIT}
     * @return 已签名的缩略图地址；入参为空时返回**空列表**（不是 {@code null} ——
     *         前端不必再判空，且"空列表"与"没有这个字段"在契约上含义不同）
     */
    public java.util.List<String> thumbs(java.util.List<String> originalUrls, int limit) {
        if (originalUrls == null || originalUrls.isEmpty()) {
            return java.util.List.of();
        }
        int max = limit <= 0 ? CARD_THUMB_LIMIT : limit;
        java.util.List<String> result = new java.util.ArrayList<>(Math.min(max, originalUrls.size()));
        for (String original : originalUrls) {
            if (result.size() >= max) {
                break;
            }
            if (original == null || original.isBlank()) {
                continue;
            }
            // ★★ 顺序是**唯一正确**的：先 derive（把 x-oss-process 拼进 query），再 sign。
            //
            //   CR-S 缺陷就出在这两行的顺序上，而且**极其隐蔽**：
            //   第一版写的是 `sign(derive(sign(original)))` —— 最内层先把**裸地址**签掉，
            //   `derive` 再把 `&x-oss-process=...` 追加到那个**已带签名**的 URL 后面。
            //   于是签名算的是"不含 x-oss-process"的 CanonicalizedResource，
            //   而实际请求带上了它 —— OSS 侧重算不一致 → **403 SignatureDoesNotMatch**。
            //
            //   为什么"看起来没问题"：返回的 URL 里 **OSSAccessKeyId / Expires / Signature 三个参数齐全**，
            //   所以"URL 里有三个签名参数"这类断言**全部放过去了**
            //   （CR-S 之前的护栏就是这么漏的）。而现象是"大图能看、缩略图 403"。
            //
            //   与 `PostService` 的 `thumbUrl` 对照就能看出差别：那条路的库里**存的就是**
            //   带 `x-oss-process` 的 thumb_url，直接对它签名 → 签名覆盖了处理参数 → 正常。
            //   本类是从**原图**推导，因此**必须 derive 在前、sign 在后**。
            //
            //   `x-oss-process` 是 OSS V1 的 SIGNED_PARAMETERS 之一，会进入
            //   CanonicalizedResource（见 V1OssReadUrlSigner.canonicalSubResources）——
            //   所以只要它在 URL 里，就必须在签名**之前**存在。
            String thumbUrl = OssThumbnailUrls.derive(original);
            if (thumbUrl == null) {
                continue;
            }
            result.add(sign(thumbUrl));
        }
        return result;
    }

    /**
     * 头像（委托 {@link AvatarUrlResolver}：它负责"是否属于该用户目录"的判定）。
     *
     * <p>本方法存在的意义是**让调用方只有一个入口可选** ——
     * 若各处既能看到 {@code AvatarUrlResolver} 又能看到本类，
     * 就会有人"顺手"用错；把它们收在同一个组件后面，选择只有一种。</p>
     */
    public String avatar(com.hyforum.domain.user.entity.User user) {
        return avatarUrlResolver.resolve(user);
    }

    /** 同上，但直接给 id 与裸地址（供手里有"刚写入的值"而不是实体的调用方用）。 */
    public String avatar(Long userId, String rawUrl) {
        return avatarUrlResolver.resolve(userId, rawUrl);
    }
}
