package com.hyforum.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记接口/方法为「免登录」。
 *
 * <p>为什么不用 Sa-Token 自带的 {@code @SaIgnore}：本项目的登录态由<b>两套独立
 * StpLogic</b>（前台 user / 后台 admin，见技术方案 §9）各自判断，拦截器需要明确知道
 * "这条路径走哪套逻辑"，因此自建一个语义更窄的注解，避免与 Sa-Token 的注解行为
 * 在两套逻辑下产生歧义。</p>
 *
 * <p>注意：注解类型不能声明静态方法（Java 语言限制），因此"是否标注了本注解"的
 * 判定逻辑放在 {@code AuthInterceptor} 里，用 Spring 的 {@code AnnotatedElementUtils}
 * 同时识别方法级与类级标注。</p>
 */
@Target({ElementType.METHOD, ElementType.TYPE})
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
public @interface AllowAnonymous {
}
