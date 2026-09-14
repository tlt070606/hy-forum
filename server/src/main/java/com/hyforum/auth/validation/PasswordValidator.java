package com.hyforum.auth.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

/**
 * {@link StrongPassword} 的校验实现。
 *
 * <p>规则（技术方案 §6.2）：8–32 位，且至少包含一个字母与一个数字。</p>
 *
 * <p>实现细节与理由：</p>
 * <ul>
 *   <li><b>长度按字符数还是字节数</b>：按<b>字符</b>数（{@code codePointCount}）计算，
 *       与用户直觉一致。中文密码虽不在白名单内，但长度判定不应因此错乱。</li>
 *   <li><b>是否限制字符集</b>：只要求"含字母与数字"，<b>不</b>额外限制长度以外的字符。
 *       契约没写"只允许字母数字"，若在这里私自收窄，会让通过前端校验的密码在后端被拒
 *       （前后端规则不一致是契约漂移的典型来源）。</li>
 *   <li><b>null 处理</b>：返回 true 交给 {@code @NotBlank} 报"不能为空"，
 *       避免同一个字段出现两条互相矛盾的提示。</li>
 * </ul>
 */
public class PasswordValidator implements ConstraintValidator<StrongPassword, String> {

    /** 最小长度（含）。 */
    public static final int MIN_LENGTH = 8;

    /** 最大长度（含）。 */
    public static final int MAX_LENGTH = 32;

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        int length = value.codePointCount(0, value.length());
        if (length < MIN_LENGTH || length > MAX_LENGTH) {
            return false;
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
        }
        return hasLetter && hasDigit;
    }
}
