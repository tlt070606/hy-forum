package com.hyforum.media.oss;

/**
 * OSS 回调公钥的来源（可替换的接缝之二）。
 *
 * <p>为什么要单独抽出来：真实实现要从 {@code gosspublic.alicdn.com} 下载 PEM，
 * 而<b>测试不能依赖外网</b>（CI 里没有，且外网抖动会让用例随机红）。
 * 把"从哪拿公钥"抽成接口/可配置前缀之后，测试把允许前缀指向本地的桩服务器，
 * 于是<b>真实的取公钥 + 真实验签代码路径</b>被完整跑通，同时完全离线。</p>
 *
 * <p>实现方的一个硬要求：<b>只允许从允许列表内的地址取公钥</b>。
 * 不校验就等于"任何人都能让我们去请求任意 URL"（SSRF），
 * 而且攻击者可以用自己控制的公钥配合自己的私钥伪造签名 —— 验签就形同虚设。</p>
 */
public interface OssPublicKeyProvider {

    /**
     * 取公钥（PEM 文本）。
     *
     * @param publicKeyUrl Base64 解码后的公钥地址
     * @return PEM 文本（形如 {@code -----BEGIN PUBLIC KEY-----...}）
     * @throws IllegalStateException 地址不被允许、下载失败或超时
     */
    String fetchPem(String publicKeyUrl);
}
