package com.hyforum.post.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OSS 基础配置（前缀 {@code aliyun.oss}，配置项已在 {@code application.yml} 里就位）。
 *
 * <p>绑定的是仓库里<b>既有</b>的配置块（提交 {@code 33bd5f0} 加的那一节），
 * 而不是 M3 另起一个前缀：同一个事实有两处配置来源，迟早会漂移，
 * 而漂移的表现是"图片能传上去但前台不显示"这类很难定位的现象。</p>
 *
 * <p><b>本类的注释刻意不复述 {@code application.yml} 里的配置块</b>（连占位形式都不复述）：
 * 那份配置由 L1 维护，在 Java 注释里抄一份就等于造了第二份会过期的事实来源 ——
 * 一旦上游改了键名或结构，注释不会报错，只会误导下一个读它的人。
 * 需要看当前取值时请直接读 {@code server/src/main/resources/application.yml} 的
 * {@code aliyun.oss} 一节。</p>
 *
 * <p><b>铁律 5：本类里没有、也不允许有 AccessKey。</b> 该配置块里的密钥属性
 * （AccessKey ID / Secret，<b>取值一律以环境变量占位</b>）由第二交付段的 {@code media} 包
 * 自行绑定（Spring 允许多个属性类绑同一前缀，本类未声明的字段直接忽略），
 * 它们只在服务端用于生成直传签名，绝不落前端、绝不落库、绝不写进任何被 git 跟踪的文件。
 * 本类只读 {@link #endpoint()}、{@link #bucketName()}、{@link #imageDir()} 三个<b>非密钥</b>项。</p>
 *
 * <p><b>第一交付段为什么需要 endpoint 与 bucket</b>：技术方案 §8.4 第 4 条要求
 * 「用户提交帖子时，后端校验图片 URL 归属（必须位于本项目 OSS 目录前缀内）」。
 * 该前缀由 bucket + endpoint 推导（{@code https://{bucket}.{endpoint}/post/}），
 * 不需要任何凭据。</p>
 *
 * <p><b>配置缺失时 fail-closed</b>：{@link #imageUrlPrefix()} 返回空串时，
 * 所有图片 URL 都会被拒绝（见 {@code PostService.resolveImageUrls}）。
 * 这比"前缀未知就放行"安全：放行会让防外链校验静默失效 ——
 * 前台渲染的图片指着别人的服务器，而日志里一个错都没有。
 * 注意 {@code application.yml} 已给 endpoint/bucket 配了本机开发默认值，
 * 因此正常本地运行不会走到 fail-closed 分支。</p>
 *
 * <p><b>归属说明（供 L1 裁决）</b>：本类放在 {@code post} 包内，因为第一交付段
 * 只有 {@code post} 需要它。第二交付段的 {@code media} 若也需要这些值，
 * 可在自己包内另建属性类绑同一前缀，或由 L1 裁决把它上移到 {@code common}／{@code media} ——
 * 那属于文件所有权问题，M3 不自行决定。</p>
 *
 * @param endpoint   OSS 地域端点，来自环境变量 {@code OSS_ENDPOINT}
 * @param bucketName Bucket 名，来自环境变量 {@code OSS_BUCKET}
 * @param imageDir   帖子图片在 Bucket 内的目录前缀；<b>必须与第二交付段签名时下发的 {@code dir} 一致</b>；
 *                   未配置时取代码默认值 {@code post/}（可用环境变量 {@code ALIYUN_OSS_IMAGE_DIR} 覆盖）
 */
@ConfigurationProperties(prefix = "aliyun.oss")
public record OssProperties(String endpoint, String bucketName, String imageDir) {

    /** 帖子图片目录（技术方案 §8.4：签名返回的 {@code dir} 采用同一前缀）。 */
    private static final String DEFAULT_IMAGE_DIR = "post/";

    public OssProperties {
        if (imageDir == null || imageDir.isBlank()) {
            imageDir = DEFAULT_IMAGE_DIR;
        }
    }

    /**
     * 本项目 OSS 的公网访问前缀：{@code https://{bucket}.{endpoint}/}。
     *
     * @return 归一化后的前缀（带结尾斜杠）；配置不全时返回空串
     *         （调用方必须把空串当作"未配置"，见类注释的 fail-closed 说明）
     */
    public String publicUrlPrefix() {
        String host = normalizeHost(endpoint);
        String bucket = normalizeHost(bucketName);
        if (host.isEmpty() || bucket.isEmpty()) {
            return "";
        }
        return "https://" + bucket + "." + host + "/";
    }

    /**
     * 帖子图片的允许前缀：公网前缀 + {@link #imageDir()}。
     *
     * <p>比 {@link #publicUrlPrefix()} 更严一层：只认本项目"帖子图片目录"下的 URL，
     * 而不是整个 Bucket —— 否则用户可以把 Bucket 里任意对象（例如将来别处的文件）
     * 当成帖子图片提交。</p>
     */
    public String imageUrlPrefix() {
        String prefix = publicUrlPrefix();
        if (prefix.isEmpty()) {
            return "";
        }
        String dir = imageDir.trim();
        while (dir.startsWith("/")) {
            dir = dir.substring(1);
        }
        if (!dir.endsWith("/")) {
            dir = dir + "/";
        }
        return prefix + dir;
    }

    /** 去掉协议头与首尾斜杠：环境变量里带不带 {@code https://}、带不带尾斜杠都能用。 */
    private static String normalizeHost(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        if (value.startsWith("https://")) {
            value = value.substring("https://".length());
        } else if (value.startsWith("http://")) {
            value = value.substring("http://".length());
        }
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
