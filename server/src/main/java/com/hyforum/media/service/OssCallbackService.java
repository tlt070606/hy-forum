package com.hyforum.media.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hyforum.domain.post.entity.PostImage;
import com.hyforum.domain.post.mapper.PostImageMapper;
import com.hyforum.media.config.OssCallbackProperties;
import com.hyforum.common.oss.OssProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;

/**
 * 回调落库：把 OSS 上传成功的对象登记为 {@code post_image} 行（技术方案 §6.8／§8.4）。
 *
 * <h2>只做"登记"，不做"归属"</h2>
 * <p>权威口径见任务书 §5 裁决 ④：回调发生的那一刻<b>帖子还不存在</b>，
 * 因此这里一律落 {@code post_id = 0}（{@code PostImage.UNBOUND_POST_ID}），
 * 由发帖时的 {@code PostService} 按 URL <b>认领</b>。
 * <b>不许猜一个真实 id</b> —— 猜错的后果是图片挂到别人的帖子上。</p>
 *
 * <p>同理：{@code audit_status} 一律写 {@code 0}（尚未被人工判定，CR-006），
 * 且 {@code thumb_url} 留空 —— 缩略图 URL 的规则在 {@code post} 模块
 * （{@code ThumbnailUrls}），认领时由它补齐。媒体模块<i>不</i>复制一份缩略图逻辑：
 * 两个模块各写一份规则，迟早分叉，而分叉的表现是"列表里的图和详情里的图不是一张"。</p>
 *
 * <h2>幂等：OSS 会重试回调</h2>
 * <p>回调返回非 2xx 时 OSS 会重试，网络抖动也可能造成重复投递。
 * 若不做幂等，同一张图会有两行 {@code post_image}：认领只会认领其中一行，
 * 另一行永远留着（列表封面/九宫格数量都会对不上）。
 * 因此这里按 URL 判重：已存在则视为成功、不重复插入。</p>
 *
 * <h2>三项内容校验（§8.4 + ADR-0007 要求"回调必须校验文件类型与大小"）</h2>
 * <ol>
 *   <li>对象 key 必须落在本项目帖子图片目录（{@code OssProperties.imageUrlPrefix()}）内 ——
 *       否则拿到签名的人可以把桶里任意对象注册成"帖子图片"；</li>
 *   <li>Content-Type 必须在白名单内（jpeg/png/webp/gif）；</li>
 *   <li>大小 ≤ 5MB。</li>
 * </ol>
 * <p>这三项与 policy 里的 OSS 侧约束构成纵深：policy 是"我们请 OSS 替我们挡"，
 * 这里是"我们自己再挡一次"，两侧都不假设对方生效。</p>
 */
@Service
public class OssCallbackService {

    private static final Logger log = LoggerFactory.getLogger(OssCallbackService.class);

    /** 回调消息体里的字段名（来源：{@code hy.oss.upload.callback-body} 模板）。 */
    private static final String FIELD_OBJECT = "object";
    private static final String FIELD_SIZE = "size";
    private static final String FIELD_MIME_TYPE = "mimeType";

    private final PostImageMapper postImageMapper;
    private final OssProperties ossProperties;
    private final OssCallbackProperties callbackProperties;
    private final ObjectMapper objectMapper;

    public OssCallbackService(PostImageMapper postImageMapper,
                              OssProperties ossProperties,
                              OssCallbackProperties callbackProperties,
                              ObjectMapper objectMapper) {
        this.postImageMapper = postImageMapper;
        this.ossProperties = ossProperties;
        this.callbackProperties = callbackProperties;
        this.objectMapper = objectMapper;
    }

    /**
     * 解析回调消息体并登记图片行。
     *
     * @param body 回调消息体原文（已通过验签）
     * @return 登记（或已存在）的图片 URL
     * @throws IllegalArgumentException 消息体不合法、或三项内容校验不通过
     *         （由调用方翻译成 400 参数错误）
     */
    @Transactional
    public String registerUploadedImage(byte[] body) {
        Map<String, Object> payload = parseBody(body);

        String objectKey = text(payload.get(FIELD_OBJECT));
        String mimeType = text(payload.get(FIELD_MIME_TYPE));
        String sizeText = text(payload.get(FIELD_SIZE));
        if (objectKey.isEmpty()) {
            throw new IllegalArgumentException("回调消息体缺少 object 字段");
        }

        String allowedPrefix = ossProperties.imageUrlPrefix();
        String url = allowedPrefix.isEmpty() ? "" : ossProperties.publicUrlPrefix() + objectKey;
        if (allowedPrefix.isEmpty() || !url.startsWith(allowedPrefix)) {
            // fail-closed：前缀未知时一律拒绝（与 post 侧的 resolveImageUrls 同一口径）
            throw new IllegalArgumentException(
                    "对象不属于本站帖子图片目录（允许前缀：" + allowedPrefix + "），object=" + objectKey);
        }

        String normalizedMime = mimeType.trim().toLowerCase(Locale.ROOT);
        if (!callbackProperties.allowedContentTypes().contains(normalizedMime)) {
            throw new IllegalArgumentException("不允许的图片类型：" + mimeType
                    + "（允许：" + callbackProperties.allowedContentTypes() + "）");
        }

        long size = parseSize(sizeText);
        if (size > callbackProperties.maxImageBytes()) {
            throw new IllegalArgumentException("图片超过大小上限：" + size + " > "
                    + callbackProperties.maxImageBytes() + " 字节");
        }

        PostImage existing = findAnyByUrl(url);
        if (existing != null) {
            // 幂等：OSS 重试造成的重复投递，直接当成功（见类注释）
            log.info("回调重复投递，已忽略：url={} 既有行 id={}", url, existing.getId());
            return url;
        }

        PostImage image = new PostImage();
        image.setPostId(PostImage.UNBOUND_POST_ID);
        image.setUrl(url);
        // thumb_url 刻意留空：缩略图规则的不变实现只有一个（post 模块的 ThumbnailUrls），
        // 发帖认领时它会补上（见类注释）
        image.setThumbUrl(null);
        image.setWidth(0);
        image.setHeight(0);
        image.setSort(0);
        image.setAuditStatus(PostImage.AUDIT_PENDING);
        image.setCreatedAt(LocalDateTime.now());
        postImageMapper.insert(image);

        log.info("回调落库成功：url={} postId={}（未认领）auditStatus={} size={}",
                url, PostImage.UNBOUND_POST_ID, PostImage.AUDIT_PENDING, size);
        return url;
    }

    /** 按 URL 找任意一行（不区分是否已认领）——幂等判重用。 */
    private PostImage findAnyByUrl(String url) {
        return postImageMapper.selectOne(Wrappers.<PostImage>lambdaQuery()
                .eq(PostImage::getUrl, url)
                .orderByAsc(PostImage::getId)
                .last("LIMIT 1"));
    }

    /**
     * 解析回调体。
     *
     * <p><b>同时支持 JSON 与表单两种编码</b>：{@code callbackBodyType} 我们下发的是
     * {@code application/json}，但 OSS 在**某些情况下**会按表单
     * （{@code application/x-www-form-urlencoded}，即它的默认值）投递回调体。
     * 只认一种编码的后果不是"报个错"那么轻 —— 真实回调会 400、
     * OSS 会把它报成 {@code CallbackFailed}，而那看起来像验签或权限问题。
     * 2026-09-16 的真实回调实测就落在这个分支上（见交付报告的证据 #6）。</p>
     *
     * @throws IllegalArgumentException 两种编码都解析不出来（消息体不含任何回调字段）
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(byte[] body) {
        if (body == null || body.length == 0) {
            throw new IllegalArgumentException("回调消息体为空");
        }
        String raw = new String(body, StandardCharsets.UTF_8).trim();
        if (raw.startsWith("{")) {
            try {
                return objectMapper.readValue(raw, Map.class);
            } catch (Exception ex) {
                logRawBodyPreview(raw, "JSON 解析失败：" + ex.getMessage());
                throw new IllegalArgumentException("回调消息体不是合法 JSON");
            }
        }
        Map<String, Object> form = parseFormEncoded(raw);
        if (!form.isEmpty()) {
            return form;
        }
        logRawBodyPreview(raw, "既不是 JSON 也不是可识别的表单编码");
        throw new IllegalArgumentException("回调消息体格式无法识别");
    }

    /** 解析 {@code k=v&k2=v2}（值按 URL 解码）。无任何 {@code =} 时返回空 Map（视为不可识别）。 */
    private static Map<String, Object> parseFormEncoded(String raw) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String value = java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            result.put(key, value);
        }
        return result;
    }

    /**
     * 解析失败时把原文前若干字符写进日志。
     *
     * <p>为什么值得打：回调体里的字段（object/bucket/size/mimeType/etag）<b>都不是敏感信息</b>，
     * 而"解析失败"这类故障，没有原文就只能靠猜 —— 真实回调每天只发生几次，
     * 靠反复重试去逼近真相的成本极高（本次实测就是靠它一次定位到编码差异）。
     * 截断到 300 字符是为了避免异常大的回调体把日志冲爆。</p>
     */
    private static void logRawBodyPreview(String raw, String reason) {
        String preview = raw.length() > 300 ? raw.substring(0, 300) + "…(截断)" : raw;
        log.warn("OSS 回调消息体解析失败（{}），原文前 300 字符：{}", reason, preview);
    }

    /** 取字符串值（缺失/非字符串都视为空串，避免 null 判定散落各处）。 */
    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** 解析 size（回调模板里它是**带引号的字符串**，见 OssUploadProperties 的说明）。 */
    private static long parseSize(String sizeText) {
        if (sizeText.isEmpty()) {
            // 缺 size 时不猜：宁可拒绝也不要放一个"未知大小"的图片进库
            throw new IllegalArgumentException("回调消息体缺少 size 字段");
        }
        try {
            return Long.parseLong(sizeText);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("回调消息体的 size 不是数字：" + sizeText);
        }
    }
}
