package com.hyforum.common.oss;

/**
 * 帖子图片的缩略图 URL 规则（**共享实现**，位于 {@code common.oss}）。
 *
 * <h2>为什么它从 {@code post.image} 搬到了这里</h2>
 * <p>回调（{@code media}）现在要返回 {@code data.thumbUrl}，而缩略图规则原本只在
 * {@code com.hyforum.post.image.ThumbnailUrls} 里 —— {@code media} 不能依赖 {@code post}（铁律 3），
 * 于是只有两条路：<b>①</b> 搬到 {@code common.oss} 两边共用；<b>②</b> 在 media 里复制一份。
 * 选 ①：复制一份就等于"同一条规则两个实现"，迟早分叉，而分叉的表现是
 * "列表里的图和详情里的图不是同一张"。这与 {@code OssProperties} 上移的理由完全一致。</p>
 *
 * <p><b>缩略图必须由 OSS 的图片处理参数生成</b>，而不是在后端转码：后端转码要把图片字节流
 * 拉回服务器（违反铁律 8 的精神 —— 文件不经过后端），还占 CPU 与内存，而 2 GiB 单机经不起。</p>
 *
 * <p>参数取 {@code resize,m_fill,w_360,h_360}：九宫格每格在手机上是约 110–120 px（三列），
 * 按 3 倍屏算 360 px 足够清晰，同时把首屏流量压到几十 KB；质量 80（OSS 默认 100），肉眼几乎无差别。</p>
 *
 * <p>⚠️ <b>与读时签名的顺序</b>：这个参数是<b>子资源</b>，OSS Signature v1 要求把它算进
 * {@code CanonicalizedResource}。所以正确顺序是「先 derive（拼上 x-oss-process）→ 再 sign」，
 * <b>绝不能</b>"先签再拼" —— 后者在真实 OSS 上的现象是"大图能看、缩略图 403"。
 * 参见 {@link OssReadUrlSigner} 的实现与测试里对签名的独立复算。</p>
 */
public final class OssThumbnailUrls {

    /** OSS 图片处理参数（缩略图口径）。 */
    public static final String RESIZE_PARAM = "x-oss-process=image/resize,m_fill,w_360,h_360/quality,q_80";

    private OssThumbnailUrls() {
    }

    /**
     * 由原图 URL 推导缩略图 URL。
     *
     * <p>已带 {@code x-oss-process} 的 URL 原样返回：二次拼接会得到
     * {@code ...&x-oss-process=...&x-oss-process=...}，OSS 只认第一个，
     * 于是"看起来设置了参数、实际不生效"，而且很难从表象看出来。</p>
     *
     * @param originalUrl 原图 URL（应已通过 OSS 前缀归属校验）
     * @return 缩略图 URL；入参为空时返回 {@code null}
     */
    public static String derive(String originalUrl) {
        if (originalUrl == null || originalUrl.isBlank()) {
            return null;
        }
        String url = originalUrl.trim();
        if (url.contains("x-oss-process=")) {
            return url;
        }
        // 已经带查询参数时用 & 连接，否则用 ?（拼错的后果是 OSS 把参数当成文件名的一部分 → 404）
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + RESIZE_PARAM;
    }
}
