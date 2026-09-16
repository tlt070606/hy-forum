package com.hyforum.post.disk;

/**
 * 归一化之后的网盘字段（docs/技术方案.md §5.5、ADR-0008）。
 *
 * <pre>
 * diskUrl  —— 干净的分享链接（不含 pwd 参数）
 * diskCode —— 提取码，可为 null（阿里云盘／夸克没有提取码机制）
 * </pre>
 *
 * <p>用 record 而不是"直接改那两个入参"：归一化的结果<b>必须两件一起返回</b>。
 * 若实现成"原地修改 URL、另一个方法返回提取码"，调用方很容易只用其中一个，
 * 于是库里出现「URL 已剥掉 pwd，但 disk_code 还是用户填的旧值」这种不一致状态 ——
 * ADR-0008 的原话是"两者始终保持一致"。</p>
 *
 * @param diskUrl  规范化后的分享链接
 * @param diskCode 提取码；无提取码时为 {@code null}（不是空字符串）
 */
public record DiskFields(String diskUrl, String diskCode) {
}
