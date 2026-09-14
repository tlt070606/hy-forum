package com.hyforum.common.security;

import jakarta.annotation.PostConstruct;
import org.springframework.context.annotation.Configuration;

/**
 * Sa-Token 两套隔离登录态的装配（技术方案 §9「后台隔离」）。
 *
 * <p>把前台（{@code user}）与后台（{@code admin}）两个常量实例登记进
 * {@link StpLogicRegistry}，由它统一分发给 {@code SaManager} 与业务代码，
 * 保证"拦截器校验的登录态"与"登录接口写入的登录态"是<b>同一个实例</b>。</p>
 *
 * <p>对照任务书 §7 的提示与 {@code 技术方案.md} §9：
 * <b>前后台隔离必须用两套独立 StpLogic，不能靠 token 前缀区分</b>。</p>
 */
@Configuration
public class SaTokenLogicConfig {

    @PostConstruct
    public void init() {
        StpLogicRegistry.register(StpUserUtil.STP);
        StpLogicRegistry.register(StpAdminUtil.STP);
    }
}
