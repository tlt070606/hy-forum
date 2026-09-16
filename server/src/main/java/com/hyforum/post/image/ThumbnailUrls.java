package com.hyforum.post.image;

/**
 * 帖子图片的 URL 处理（技术方案 §8.4）。
 *
 * <p>缩略图<b>必须由 OSS 的图片处理参数生成</b>，而不是在后端转码：
 * 后端转码要把图片字节流拉回服务器（违反铁律 8 的精神 —— 文件不经过后端），
 * 还要占 CPU 与内存，而 2 GiB 单机经不起。</p>
 *
 * <p>参数取 {@code resize,m_fill,w_360,h_360}：九宫格每格在手机上是
 * 约 110–120 px（三列），按 3 倍屏算 360 px 足够清晰，同时把首屏流量压到几十 KB。
 * 质量取 80（OSS 默认 100），肉眼几乎无差别。</p>
 */
public final class ThumbnailUrls {

    /** OSS 图片处理参数（缩略图口径）。 */
    public static final String RESIZE_PARAM = "x-oss-process=image/resize,m_fill,w_360,h_360/quality,q_80";

    private ThumbnailUrls() {
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
