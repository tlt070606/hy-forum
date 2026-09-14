package com.hyforum.auth;

import com.hyforum.auth.validation.PasswordValidator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 密码强度规则（docs/技术方案.md §6.2「注册校验规则」）。
 *
 * <p>对应验收项：{@code M1_weak_password_rejected} —— 8–32 位且含字母+数字。</p>
 *
 * <p>层次是「单元」：这里直接测校验器 {@link PasswordValidator}，不起 Spring 容器、
 * 不连数据库 —— 规则本身与持久化无关，把它绑到集成测试上只会让这条最基础的规则
 * 因为环境问题（Redis/MySQL 不可用）而红。</p>
 *
 * <p>覆盖的边界（每一条都对应"实现里最容易写错的一处"）：</p>
 * <ul>
 *   <li>长度下界 8 / 上界 32 的两个边界值本身必须<b>通过</b>（off-by-one 高发区）；</li>
 *   <li>7 位与 33 位必须被拒；</li>
 *   <li>纯数字、纯字母必须被拒（"至少含字母与数字"是双向要求）；</li>
 *   <li>大小写混合、字母+数字+符号必须通过（契约没有限制字符集，
 *       若实现私自收窄，在这里会立刻暴露）。</li>
 * </ul>
 */
@DisplayName("M1 · 密码强度规则")
class M1PasswordStrengthTest {

    private final PasswordValidator validator = new PasswordValidator();

    /** 直接调用校验器（不带校验上下文，实现里没有用到它）。 */
    private boolean valid(String password) {
        return validator.isValid(password, null);
    }

    @Test
    void M1_weak_password_rejected() {
        // ---------- 合法：边界值本身必须通过 ----------
        assertThat(valid("a2345678")).as("正好 8 位（下界）且含字母+数字，必须通过").isTrue();
        assertThat(valid("a2345678901234567890123456789012"))
                .as("正好 32 位（上界）且含字母+数字，必须通过").isTrue();
        assertThat(valid("Passw0rd!@#")).as("契约未限制字符集，符号密码必须通过").isTrue();

        // ---------- 非法：长度 ----------
        assertThat(valid("a234567")).as("7 位必须被拒（下界外）").isFalse();
        assertThat(valid("a23456789012345678901234567890123")).as("33 位必须被拒（上界外）").isFalse();

        // ---------- 非法：字符构成 ----------
        assertThat(valid("12345678")).as("纯数字必须被拒").isFalse();
        assertThat(valid("abcdefgh")).as("纯字母必须被拒").isFalse();
        assertThat(valid("!@#$%^&*")).as("无字母无数字必须被拒").isFalse();

        // ---------- null 交给 @NotBlank 报"不能为空"，校验器不越权报错 ----------
        assertThat(valid(null)).as("null 由 @NotBlank 负责，校验器返回 true 避免两条矛盾提示").isTrue();
    }
}
