package com.hyforum.media.oss;

/**
 * 回调验签器（可替换的接缝）。
 *
 * <p>为什么是接口：任务书 §6.2 的 ★ 指出本段最核心的假绿陷阱是
 * 「验签器实现成永远拒绝 ⇒ 拒绝用例必然通过，而整条链路永远没有一张图能落库」。
 * 把验签抽成接口之后，"验签通过"这条路可以被独立地、真实地测出来
 * （测试用本地 RSA 密钥对与本地公钥桩走完整验签代码路径），
 * 而不是只能测"拒绝"。</p>
 *
 * <p>实现必须<b>只依赖请求数据与公钥来源</b>，不得依赖"当前登录用户"——
 * 回调由 OSS 发起，没有登录态（任务书 §5 裁决 ③）。</p>
 */
public interface OssCallbackVerifier {

    /**
     * 验签。
     *
     * @param request 回调请求的验签输入
     * @return 结果（含失败原因，仅用于日志）
     */
    OssVerifyResult verify(OssCallbackRequest request);
}
