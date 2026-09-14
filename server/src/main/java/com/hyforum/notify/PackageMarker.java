package com.hyforum.notify;

/**
 * 消息通知模块（{@code com.hyforum.notify}）的包级占位声明。
 *
 * <p>存在的理由见 {@code com.hyforum.post.PackageMarker}：让 ArchUnit 的
 * 七包互不依赖规则（铁律 3）在编译产物里真正有对象可查。</p>
 *
 * <p><b>归属</b>：W2 的 M5 任务（点赞/评论/回复/关注通知、未读条数）。</p>
 */
public final class PackageMarker {

    private PackageMarker() {
    }

    /** 包名常量，供架构测试引用。 */
    public static final String PACKAGE = "com.hyforum.notify";
}
