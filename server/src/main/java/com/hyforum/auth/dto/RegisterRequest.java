package com.hyforum.auth.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.hyforum.auth.validation.StrongPassword;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 注册请求（docs/技术方案.md §6.2）：
 * {@code username、password、nickname、captchaUuid、captchaCode、agreeProtocol、inviteCode}
 *
 * <p>逐字段对应契约，<b>不增不减</b>。校验规则（§6.2「注册校验规则」）：</p>
 * <ul>
 *   <li>username：4–20 位字母数字下划线，且唯一（唯一性需查库，在 Service 里判）；</li>
 *   <li>password：8–32 位且至少含字母与数字（由 {@code @ValidPassword} 承担）；</li>
 *   <li>nickname：1–20 字符；</li>
 *   <li>agreeProtocol：必须为 true，否则拒绝（合规要求，对应验收项 M1_agree_protocol_required）；</li>
 *   <li>inviteCode：邀请制下必填（是否必填取决于注册模式，属于业务规则，不在注解里写死）。</li>
 * </ul>
 *
 * @param username      登录名
 * @param password      明文密码。<b>WRITE_ONLY</b>：序列化时永不输出，避免任何日志/回显泄露
 * @param nickname      昵称
 * @param captchaUuid   验证码标识
 * @param captchaCode   用户输入的验证码
 * @param agreeProtocol 是否同意用户协议
 * @param inviteCode    邀请码（open/closed 模式下可忽略）
 */
public record RegisterRequest(
        @NotBlank(message = "用户名不能为空")
        @Size(min = 4, max = 20, message = "用户名长度需为 4-20 位")
        String username,

        @NotBlank(message = "密码不能为空")
        @StrongPassword
        @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
        String password,

        @NotBlank(message = "昵称不能为空")
        @Size(min = 1, max = 20, message = "昵称长度需为 1-20 字符")
        String nickname,

        @NotBlank(message = "验证码标识不能为空")
        String captchaUuid,

        @NotBlank(message = "验证码不能为空")
        String captchaCode,

        @NotNull(message = "必须明确是否同意用户协议")
        Boolean agreeProtocol,

        String inviteCode) {
}
