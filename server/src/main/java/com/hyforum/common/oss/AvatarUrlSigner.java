package com.hyforum.common.oss;

/**
 * 头像的读时签名（**接口**，位于 {@code common}，<b>不持有凭据</b>）。
 *
 * <h2>为什么头像需要一个"自己的"签名入口，而不是复用 {@link OssReadUrlSigner}</h2>
 * <p>{@code OssReadUrlSigner.sign(url)} 判定的是<b>帖子图片目录</b>
 * （{@code OssProperties.imageUrlPrefix()} = {@code post/}）。头像在 {@code avatar/{userId}/} 下，
 * 因此拿它去签头像会一律"原样返回"（实现里的前置判定不通过），
 * 结果就是**头像不带签名、前端渲染必然 403**（桶是私有的）。
 * 这正是 CR-Q 报告的现象：帖子图能看、头像不能。</p>
 *
 * <p>所以头像的签名必须能表达"**这个 key 属于哪个用户的目录**"。
 * 本接口把 {@code userId} 作为参数，就是为了让"目录归属"这件事在签名这一层也是显式的 ——
 * 而不是让调用方自己拼前缀再传字符串（那又是一个可以拼错、可以漂移的地方）。</p>
 *
 * <h2>实现方必须遵守（与 {@link OssReadUrlSigner} 同一套纪律）</h2>
 * <ol>
 *   <li><b>不要缓存签名结果</b>：它带 {@code Expires}，是临时的；库里存裸 URL，读时现签；</li>
 *   <li><b>只签该用户自己目录下的 URL</b>：外部 URL、别人的目录、{@code null}/空白、
 *       已签过的 URL 一律<b>原样返回</b>；</li>
 *   <li><b>有效期与帖子图同一口径</b>（同一个 {@code hy.oss.read-url-ttl}），
 *       不许为头像另设一套 —— 否则会出现"帖子图还能看、头像先过期"这种没人能解释的现象。</li>
 * </ol>
 */
public interface AvatarUrlSigner {

    /**
     * 给某个用户的头像 URL 签发临时读取签名。
     *
     * @param userId 头像所属用户（决定"允许前缀"是 {@code avatar/{userId}/}）
     * @param url    库里存的裸对象 URL；{@code null}/空白、非该用户目录下的、
     *               以及已签过的一律原样返回
     * @return 可直接 GET 的签名 URL；不需要/不能签名时返回入参本身
     */
    String sign(long userId, String url);
}
