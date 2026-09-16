package com.hyforum.post.vo;

import com.hyforum.domain.post.entity.PostImage;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 帖子图片（详情页九宫格的每一项）。
 *
 * <p>{@code url} 给"点开看大图"，{@code thumbUrl} 给九宫格渲染 ——
 * 两者都要给：只给缩略图时用户点开看到的是模糊图，只给原图时首屏流量翻十倍。</p>
 *
 * <p>生成的缩略图 URL 由 OSS 图片处理参数拼出（{@code x-oss-process=...}），
 * 见 {@code com.hyforum.post.image.ThumbnailUrls}。</p>
 *
 * <p>本 VO <b>不返回 {@code auditStatus}</b>：前台看不到"这张图在审核中"这种信息
 * （CR-006 的口径是"隐藏已判违规的图"，其余一律正常展示）。
 * 图片审核出口在 M5/M6，届时由管理端接口读取该字段。</p>
 *
 * @param id       图片 id
 * @param url      原图 URL
 * @param thumbUrl 缩略图 URL（OSS 图片处理参数）
 * @param width    宽（回调写入；未提供时为 0）
 * @param height   高
 * @param sort     展示顺序（0 起）
 */
@Schema(name = "PostImageVO", description = "帖子图片")
public record PostImageVO(Long id, String url, String thumbUrl, Integer width, Integer height, Integer sort) {

    public static PostImageVO from(PostImage image) {
        if (image == null) {
            return null;
        }
        return new PostImageVO(image.getId(), image.getUrl(), image.getThumbUrl(),
                image.getWidth(), image.getHeight(), image.getSort());
    }
}
