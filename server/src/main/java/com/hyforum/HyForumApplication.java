package com.hyforum;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * Hy论坛 后端启动类。
 *
 * <p>工程口径见 docs/技术方案.md §3.3 的 v1.8 补充：<b>单 Maven 模块 + 分层 package</b>，
 * 基础包 {@code com.hyforum}；七个业务包（auth / post / media / interaction / audit /
 * notify / admin）之间禁止相互依赖，只允许依赖 {@code common} 与 {@code domain}
 * —— 这条铁律 3 由 {@code ARCH_no_cross_module_dependency} 用 ArchUnit 守门。</p>
 *
 * <p>Mapper 扫描放在这里而不是各业务包内：Mapper 归 {@code com.hyforum.domain} 所有，
 * 是跨模块共享的，统一注册可以避免各业务包自己去 {@code @MapperScan} 造成扫描范围分叉。</p>
 *
 * <p>{@code @ConfigurationPropertiesScan} 同样放在根上：各业务包（auth 的注册模式参数等）
 * 都要能声明自己的配置属性记录，逐个 {@code @EnableConfigurationProperties} 会漏。</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.hyforum")
@MapperScan("com.hyforum.domain.**.mapper")
public class HyForumApplication {

    public static void main(String[] args) {
        SpringApplication.run(HyForumApplication.class, args);
    }
}
