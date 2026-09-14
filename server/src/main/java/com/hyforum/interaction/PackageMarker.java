package com.hyforum.interaction;

/**
 * 互动模块（{@code com.hyforum.interaction}：评论 / 点赞 / 收藏 / 关注）的包级占位声明。
 *
 * <p>存在的理由见 {@code com.hyforum.post.PackageMarker}：让 ArchUnit 的
 * 七包互不依赖规则（铁律 3）在编译产物里真正有对象可查。</p>
 *
 * <p><b>归属</b>：W2 的 M4 任务。注意该里程碑的两条已定案红线：
 * P1-3 楼中楼归并语义（{@code parent_id} 恒等于 {@code root_id}，唯一写入口）、
 * P1-4 邀请码条件更新（本项目里邀请码属 auth，此处仅作交叉提醒）。
 * 广播见 docs/agents/工作计划.md §4。</p>
 */
public final class PackageMarker {

    private PackageMarker() {
    }

    /** 包名常量，供架构测试引用。 */
    public static final String PACKAGE = "com.hyforum.interaction";
}
