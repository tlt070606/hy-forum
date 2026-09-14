package com.hyforum.auth.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 密码强度校验：<b>8–32 位，且至少包含一个字母与一个数字</b>
 * （docs/技术方案.md §6.2「注册校验规则」）。
 *
 * <p>做成注解而不是在 Service 里 if：这样密码规则有<b>唯一</b>落点，未来（例如
 * 修改密码、管理员重置密码）复用同一规则时不会各自复制一份判断而漂移。</p>
 *
 * <p>命名说明：类名用 {@code StrongPassword} 而不是 {@code ValidPassword}，
 * 是为了遵守本项目的命名规范 —— 注解不加 {@code Valid} 前缀，
 * 由配套校验器 {@link PasswordValidator} 承担 {@code ConstraintValidator} 语义。</p>
 */
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Constraint(validatedBy = PasswordValidator.class)
public @interface StrongPassword {

    String message() default "密码需为 8-32 位，且至少包含字母与数字";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
