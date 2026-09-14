package com.hyforum.common.web;

import com.hyforum.common.security.AccountStatusChecker;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 层装配：注册登录态拦截器。
 *
 * <p>拦截范围选 {@code /api/**} 而不是 {@code /**}：接口文档（knife4j /doc.html、
 * /v3/api-docs）与 {@code /actuator/health} 都不带业务数据，没必要也不应该被登录态拦住
 * —— 否则健康检查会被判 401，运维监控直接失效（技术方案 §11 监控项）。</p>
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final AccountStatusChecker accountStatusChecker;

    public WebMvcConfig(AccountStatusChecker accountStatusChecker) {
        this.accountStatusChecker = accountStatusChecker;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new AuthInterceptor(accountStatusChecker))
                .addPathPatterns("/api/**")
                // 登录/注册/验证码/注册模式查询都是免登录接口，但**仍走拦截器**：
                // 由 @AllowAnonymous 注解决定放行，避免"路径白名单"与"注解"两套机制并存。
                .order(0);
    }
}
