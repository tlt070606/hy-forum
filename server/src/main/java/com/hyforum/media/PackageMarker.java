package com.hyforum.media;

/**
 * OSS 直传与回调模块（{@code com.hyforum.media}）的包级占位声明。
 *
 * <p>存在的理由见 {@code com.hyforum.post.PackageMarker}：让 ArchUnit 的
 * 七包互不依赖规则（铁律 3）在编译产物里真正有对象可查。</p>
 *
 * <p><b>归属</b>：W2 的 M3 任务（OSS 服务端签名直传、回调验签）。
 * 铁律 8 与 iron rule 5 在此包内落地：AccessKey 只允许来自环境变量，绝不进前端。</p>
 */
public final class PackageMarker {

    private PackageMarker() {
    }

    /** 包名常量，供架构测试引用。 */
    public static final String PACKAGE = "com.hyforum.media";
}
