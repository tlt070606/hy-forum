package com.hyforum.post.disk;

import java.util.Locale;

/**
 * 网盘类型（docs/技术方案.md §5.3 的 {@code post.disk_type} 列注释）。
 *
 * <table>
 *   <caption>取值</caption>
 *   <tr><td>1</td><td>百度网盘（可带 {@code pwd} 提取码）</td></tr>
 *   <tr><td>2</td><td>阿里云盘（<b>无提取码机制</b>）</td></tr>
 *   <tr><td>3</td><td>夸克网盘（<b>无提取码机制</b>）</td></tr>
 *   <tr><td>4</td><td>天翼云盘</td></tr>
 *   <tr><td>5</td><td>迅雷网盘</td></tr>
 *   <tr><td>6</td><td>其他</td></tr>
 * </table>
 *
 * <p><b>为什么要有这个枚举</b>：{@code disk_type} 是一个 TINYINT，若代码里到处写裸数字，
 * "3 是夸克还是天翼"这种问题每次都要回去翻文档，而写错一个是不会报错的 ——
 * 用户会看到"阿里云盘"标签配着夸克的链接。用枚举把数值与语义绑在一处，
 * 非法值也能在入口处被明确拒绝（而不是落到库里变成一个前台不认识的数字）。</p>
 *
 * <p>3 与 2 这两类<b>允许 {@code disk_code} 为空</b>（ADR-0008 决策 1），
 * 但本枚举不为此开特例：契约里 {@code disk_code} 对<b>所有</b>网盘都可选
 * （用户可能把访问码写在正文里）。ADR-0008 关心的是"字段必须允许为空"，
 * 而不是"哪几类不允许填"。</p>
 */
public enum DiskType {

    /** 1 百度网盘。 */
    BAIDU(1, "百度网盘"),

    /** 2 阿里云盘（无提取码机制）。 */
    ALIPAN(2, "阿里云盘"),

    /** 3 夸克网盘（无提取码机制）。 */
    QUARK(3, "夸克网盘"),

    /** 4 天翼云盘。 */
    TIANYI(4, "天翼云盘"),

    /** 5 迅雷网盘。 */
    XUNLEI(5, "迅雷网盘"),

    /** 6 其他。 */
    OTHER(6, "其他");

    private final int code;
    private final String label;

    DiskType(int code, String label) {
        this.code = code;
        this.label = label;
    }

    /** 入库值。 */
    public int code() {
        return code;
    }

    /** 展示名（错误信息与日志用）。 */
    public String label() {
        return label;
    }

    /**
     * 按入库值反查。
     *
     * @param code 入库值；{@code null} 返回 {@code null}（"没填"是合法状态：非资源版块）
     * @return 对应枚举；取值不在 1–6 之内返回 {@code null}（由调用方转成 400，
     *         而不是抛异常 —— 这类输入错误属于"用户填错了"，不是程序缺陷）
     */
    public static DiskType of(Integer code) {
        if (code == null) {
            return null;
        }
        for (DiskType each : values()) {
            if (each.code == code) {
                return each;
            }
        }
        return null;
    }

    /** 合法取值的说明文本（拼错误信息用）。 */
    public static String legalValues() {
        StringBuilder builder = new StringBuilder();
        for (DiskType each : values()) {
            if (builder.length() > 0) {
                builder.append(" / ");
            }
            builder.append(each.code).append('=').append(each.label);
        }
        return builder.toString();
    }

    /** 仅用于日志的短名（避免把中文标签写进结构化日志）。 */
    public String shortName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
