package com.hyforum.common.oss;

/**
 * 读时签名（**接口**，位于 {@code common}，<b>不持有凭据</b>）。
 *
 * <h2>为什么需要"读时签名"</h2>
 * <p>桶是<b>私有的</b>（匿名 GET 对象 → {@code 403 AccessDenied ... because of bucket acl}），
 * 而 {@code post_image.url} 存的是裸公网 URL；前端直接渲染裸 URL 会 403 ——
 * 也就是"带图帖在详情页必然显示不出来"。想把桶设成公共读又被账号级「阻止公共访问」拦住
 * （{@code 403 Put public bucket acl is not allowed}）。因此由后端在<b>读</b>的时候签发临时 URL。</p>
 *
 * <h2>为什么是接口 + 实现分离（铁律 3）</h2>
 * <p>{@code post} 需要签名能力，但实现要用 {@code media} 包里的凭据属性 ——
 * 若 {@code post} 直接依赖 {@code media}，就是跨模块调用，ArchUnit 会拦。
 * 于是：接口放在 {@code common.oss}（不持凭据，谁都能依赖），
 * 实现放在 {@code media} 并由 Spring 注入。这与 {@code common.audit.SensitiveTextChecker}
 * 的既有做法同源。</p>
 *
 * <h2>调用方的两条纪律</h2>
 * <ol>
 *   <li><b>不要缓存签名后的 URL</b>：它带 {@code Expires}，是<b>临时</b>的。
 *       数据库里存的必须是裸 URL，每次对外组装响应时现签。</li>
 *   <li><b>只签本项目 OSS 目录下的 URL</b>（实现里按 {@link OssProperties#imageUrlPrefix()} 判定）——
 *       外部 URL 原样返回，不签（也不该被"顺手"加上签名参数）。</li>
 * </ol>
 */
public interface OssReadUrlSigner {

    /**
     * 给一个 OSS 对象 URL 签发临时读取签名。
     *
     * @param url 裸对象 URL（可带子资源参数，例如 {@code ?x-oss-process=...}）；
     *            非本项目 OSS 目录下的 URL、{@code null}/空白、以及**已经签过**的 URL 一律原样返回
     * @return 可直接 GET 的签名 URL（含 {@code OSSAccessKeyId}/{@code Expires}/{@code Signature}）
     */
    String sign(String url);
}
