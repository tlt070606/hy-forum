package com.hyforum.common.audit;

/**
 * 敏感内容检查点（抽象，不是实现）。
 *
 * <p><b>为什么在 M1 就定义</b>：契约 §6.1 把错误码 {@code 2001 内容包含敏感词} 定为全网通用错误码，
 * 而"内容包含敏感词"的判定必须由业务入口调用。M1 的注册接口会写入昵称等自由文本，
 * 属于内容入口，因此需要一个可调用的检查点，否则 2001 在本里程碑就是一个定义不出触发路径的死码。</p>
 *
 * <p><b>为什么是接口而不是直接依赖实现</b>：铁律 3 要求业务包之间互不依赖。
 * 完整的敏感词能力（{@code ContentAuditService}：{@code auditText}/{@code auditImage}，
 * 见技术方案 §8.6）属于 M5 的 {@code com.hyforum.audit} 包。若 M1 的 auth 直接依赖它，
 * 就是 auth → audit 的跨模块调用。因此这里只声明"能力"，实现由 audit 包提供。</p>
 *
 * <p><b>与 M5 的边界（明确交接）</b>：M1 只提供"能按敏感词表拦截"的最小实现；
 * §8.6 要求的 DFA 算法、启动加载与内存刷新策略、图片审核、降级策略、
 * 审核队列都是 M5 的范围。M5 实现 {@code ContentAuditService} 时可以替换本接口的实现类，
 * auth 侧调用点无需改动。</p>
 */
public interface SensitiveTextChecker {

    /**
     * 文本是否命中敏感词。
     *
     * <p>约定：{@code null} / 空白视为未命中（调用方负责非空校验）。</p>
     *
     * @param text 待检查文本
     * @return true 表示命中敏感词，调用方应拒绝并返回 2001
     */
    boolean containsSensitive(String text);
}
