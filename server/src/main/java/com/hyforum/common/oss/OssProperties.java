package com.hyforum.common.oss;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * OSS 基础配置（前缀 {@code aliyun.oss}，配置项在 {@code application.yml} 里，由 L1 维护）。
 *
 * <p><b>本类位于 {@code common}，由 {@code post} 与 {@code media} 两个业务包共同注入</b>
 * （2026-09-16 L1 裁决；此前它在 {@code com.hyforum.post.config}）。
 * 理由：同一个配置前缀若由两个包各自绑一份，就是"同一事实两处映射"，迟早漂移，
 * 而漂移的表现是"图片能传上去但前台不显示"这类极难定位的现象。
 * 放进 {@code common} 同时满足了铁律 3 —— {@code post} 与 {@code media} 之间
 * <b>不得</b>互相依赖（{@code ARCH_no_cross_module_dependency} 会拦）。</p>
 *
 * <p><b>本类的注释刻意不复述 {@code application.yml} 里的配置块</b>（连占位形式都不复述）：
 * 那份配置由 L1 维护，在 Java 注释里抄一份就等于造了第二份会过期的事实来源 ——
 * 一旦上游改了键名或结构，注释不会报错，只会误导下一个读它的人。
 * 需要看当前取值时请直接读 {@code server/src/main/resources/application.yml} 的
 * {@code aliyun.oss} 一节。</p>
 *
 * <p><b>铁律 5：本类里没有、也不允许有 AccessKey。</b> 两个密钥属性由 {@code media} 包
 * 自己的属性类绑定（同一前缀，Spring 允许多个属性类绑同一前缀；本类未声明的字段直接忽略）。
 * 这是<b>最小暴露</b>而不是洁癖：{@code post} 也注入本类，而它只需要
 * {@link #endpoint()}、{@link #bucketName()}、{@link #imageDir()} 三个非密钥项；
 * 密钥被不相关的模块读到，将来就会有人在不相关的地方用它。</p>
 *
 * <p><b>{@code post} 为什么需要 endpoint 与 bucket</b>：技术方案 §8.4 第 4 条要求
 * 「用户提交帖子时，后端校验图片 URL 归属（必须位于本项目 OSS 目录前缀内）」。
 * 该前缀由 bucket + endpoint 推导（{@code https://{bucket}.{endpoint}/post/}），
 * 不需要任何凭据。</p>
 *
 * <p><b>{@code media} 为什么需要它们</b>：直传签名要下发 {@code host}（供前端 POST 表单用），
 * 且签名里 {@code dir} 必须与 {@link #imageDir()} **同源** ——
 * 否则"签名的目录"与"后端校验的前缀"不一致，前端传上去的图后端不认。</p>
 *
 * <p><b>配置缺失时 fail-closed</b>：{@link #imageUrlPrefix()} 返回空串时，
 * 所有图片 URL 都会被拒绝（见 {@code PostService.resolveImageUrls}）。
 * 这比"前缀未知就放行"安全：放行会让防外链校验静默失效 ——
 * 前台渲染的图片指着别人的服务器，而日志里一个错都没有。
 * 注意 {@code application.yml} 已给 endpoint/bucket 配了本机开发默认值，
 * 因此正常本地运行不会走到 fail-closed 分支。</p>
 *
 * @param endpoint   OSS 地域端点，来自环境变量 {@code OSS_ENDPOINT}
 * @param bucketName Bucket 名，来自环境变量 {@code OSS_BUCKET}
 * @param imageDir   帖子图片在 Bucket 内的目录前缀；<b>签名下发的 {@code dir} 必须与它同源</b>；
 *                   未配置时取代码默认值 {@code post/}（可用环境变量 {@code ALIYUN_OSS_IMAGE_DIR} 覆盖）
 */
@ConfigurationProperties(prefix = "aliyun.oss")
public record OssProperties(String endpoint, String bucketName, String imageDir) {

    /** 帖子图片目录（技术方案 §8.4：签名返回的 {@code dir} 采用同一前缀）。 */
    private static final String DEFAULT_IMAGE_DIR = "post/";

    /**
     * 头像目录的**基础段**（§14.2 ②：两个目录只在 {@link OssProperties} 里定义一次）。
     *
     * <p>为什么不做成配置项：{@code application.yml} 由 L1 独占，加键要走 CR；
     * 而 {@link #imageDir()} 之所以是配置项，是因为**它先于本类存在**（M3 第二交付段就在用）。
     * 这里刻意<b>不</b>为了对称硬加一个键 —— 「为对称而加配置」会让配置面变大而收益为零。
     * 已提 CR 建议给头像目录也加键（见交付报告）；在它被批准前，唯一的字面量就在这一行。</p>
     *
     * <p><b>注意它不是"头像目录"，而是头像目录的父目录</b>：真正的头像目录是
     * {@code avatar/{userId}/} —— 见 {@link #userAvatarDir(long)}。
     * 按用户再分一层是本任务钉死的设计边界（§14.2 ①）：否则用户 A 可以把头像设成
     * 用户 B 上传的任意对象，而校验没有依据说"不"。</p>
     */
    private static final String AVATAR_DIR = "avatar/";

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
            // fail-closed：前缀未知时返回空串，调用方据此拒绝一切图片 URL（见类注释）
            return "";
        }
        return prefix + normalizedDir(imageDir);
    }

    /**
     * 某个用户的头像目录：{@code avatar/{userId}/}（§14.2 ①，L1 钉死）。
     *
     * <p><b>为什么必须带 {@code userId}</b>：头像的归属校验比帖子图更严 ——
     * 帖子图只要求"在本项目的帖子目录下"，而头像若不按用户分目录，
     * 用户 A 就能把头像设成<b>用户 B 上传的任意对象</b>（B 删了头像，A 的也变）；
     * 分了目录之后，校验才有依据说"<b>你只能用你自己目录下的对象</b>"。</p>
     *
     * <p>这是**唯一**生成头像目录的地方：签名下发的 {@code dir} 与后端校验用的前缀
     * 都从这里取（{@link #userAvatarUrlPrefix(long)} = 公网前缀 + 本方法的返回值）。
     * 各处各写一个字面量 {@code "avatar/"} 就是本项目反复吃亏的"同一事实两处映射"。</p>
     *
     * @param userId 用户 id；必须为正数（0 或负数说明调用方拿错了值 ——
     *               静默拼出 {@code avatar/0/} 会让"谁的目录"这件事变得无法解释）
     */
    public String userAvatarDir(long userId) {
        if (userId <= 0) {
            throw new IllegalArgumentException("头像目录需要真实的用户 id，收到：" + userId);
        }
        return normalizedDir(AVATAR_DIR) + userId + "/";
    }

    /**
     * 某个用户头像的允许 URL 前缀：公网前缀 + {@link #userAvatarDir(long)}。
     *
     * <p>与 {@link #imageUrlPrefix()} 同一形状 —— 校验逻辑因此可以完全对称：
     * {@code url.startsWith(前缀)}。配置不全时同样返回空串（fail-closed，见类注释）。</p>
     */
    public String userAvatarUrlPrefix(long userId) {
        String prefix = publicUrlPrefix();
        if (prefix.isEmpty()) {
            // 与 imageUrlPrefix() 完全对称的 fail-closed：前缀未知 → 空串 → 调用方拒绝
            return "";
        }
        return prefix + userAvatarDir(userId);
    }

    /** 头像目录的父段（{@code avatar/}），供"必须给头像签名"的地方判别用。 */
    public String avatarDir() {
        return normalizedDir(AVATAR_DIR);
    }

    /**
     * 归一化目录：去首尾斜杠后统一补一个结尾斜杠。
     *
     * <p>抽出来是为了让"目录归一化"只有一处实现 —— 帖子目录与头像目录的行为必须一致，
     * 而两处各写一遍 {@code while (startsWith("/"))} 迟早会出现一处漏判。</p>
     */
    private static String normalizedDir(String raw) {
        String dir = raw == null ? "" : raw.trim();
        while (dir.startsWith("/")) {
            dir = dir.substring(1);
        }
        return dir.endsWith("/") ? dir : dir + "/";
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
