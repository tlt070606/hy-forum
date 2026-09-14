package com.hyforum.post;

/**
 * 版块与帖子模块（{@code com.hyforum.post}）的包级占位声明。
 *
 * <p><b>为什么这里有一个空的类</b>：docs/技术方案.md §3.3 v1.8 定死了七个业务包，
 * 铁律 3 的可自动化校验（ArchUnit：七个业务包之间禁止相互依赖）要求这些包
 * <b>真实存在于编译产物中</b>。若某个包在本里程碑还没有任何类，ArchUnit 的
 * {@code noClasses().that().resideInAPackage(...)} 规则会因为"没有类"而变得无从校验，
 * 形成"看起来通过了、其实没检查"的假绿。</p>
 *
 * <p>因此每个尚未开工的业务包都放一个显式的包声明类：
 * 它既让架构规则真正生效，也在代码里留下"这个包归谁、什么时候填"的记录，
 * 避免后来者以为是废弃代码而删掉。</p>
 *
 * <p><b>归属</b>：W2 的 M3 任务（版块 / 帖子 / 网盘字段归一化）。
 * 见 docs/agents/工作计划.md §2 的所有权表。本类不含任何业务逻辑，M3 可直接删除。</p>
 */
public final class PackageMarker {

    private PackageMarker() {
    }

    /** 包名常量，供架构测试引用，避免测试里再拼一遍字符串。 */
    public static final String PACKAGE = "com.hyforum.post";
}
