package com.hyforum.auth.mode;

import java.util.Locale;

/**
 * 注册模式（docs/技术方案.md §8.8）。
 *
 * <p>取值存在 {@code sys_config} 表的 {@code register_mode} 配置项里，
 * <b>运行期可切换、无需重启</b>（验收项 {@code M6_register_mode_switch_effective}）。</p>
 *
 * <table>
 *   <caption>取值语义</caption>
 *   <tr><td>{@code open}（默认）</td><td>任何人可注册，需通过图形验证码</td></tr>
 *   <tr><td>{@code invite}</td><td>必须提供有效邀请码；用于备案受阻时的降级</td></tr>
 *   <tr><td>{@code closed}</td><td>完全关闭注册，仅保留已注册用户登录</td></tr>
 * </table>
 *
 * <p><b>必须是白名单枚举而不是字符串比较</b>：数据库里存的是自由文本
 * （{@code VARCHAR(500)}），若代码里散落 {@code "open".equals(...)}，一旦运营把值改成
 * {@code Open} 或 {@code OPEN}，会得到"注册被静默拒绝"这类难以排查的故障。</p>
 */
public enum RegisterMode {

    /** 开放注册（默认）。 */
    OPEN("open"),

    /** 邀请码注册。 */
    INVITE("invite"),

    /** 关闭注册。 */
    CLOSED("closed");

    private final String value;

    RegisterMode(String value) {
        this.value = value;
    }

    /** 配置项里的字面值。 */
    public String value() {
        return value;
    }

    /**
     * 解析配置值。
     *
     * <p>容错口径：忽略前后空白、忽略大小写；<b>无法识别时抛异常</b>而不是退回默认值 ——
     * 若静默退回 {@code open}，运维把值填错就等于"注册门被人为打开"，这是安全方向的错误选择；
     * 抛异常会让注册接口明确失败，能被立刻发现。</p>
     *
     * @param raw 配置项原文（可为 null，表示配置缺失）
     * @return 解析结果；{@code null} 输入返回 {@link #OPEN}（配置缺失时的契约默认值）
     */
    public static RegisterMode parse(String raw) {
        if (raw == null || raw.isBlank()) {
            return OPEN;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT);
        for (RegisterMode each : values()) {
            if (each.value.equals(normalized)) {
                return each;
            }
        }
        throw new IllegalStateException("sys_config.register_mode 取值非法：" + raw
                + "（合法值：open / invite / closed）");
    }
}
