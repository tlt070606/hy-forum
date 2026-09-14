package com.hyforum.common.config;

import com.baomidou.mybatisplus.annotation.DbType;
import com.baomidou.mybatisplus.extension.plugins.MybatisPlusInterceptor;
import com.baomidou.mybatisplus.extension.plugins.inner.PaginationInnerInterceptor;
import com.hyforum.common.api.PageResult;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 基础设施 Bean 装配。
 */
@Configuration
public class CommonConfig {

    /**
     * 分页插件：<b>必须显式设置 {@code maxLimit}</b>。
     *
     * <p>为什么重要：契约要求分页硬上限 20（docs/ops/deployment.md §5，
     * 验收项 {@code M3_page_size_hard_cap_20}）。只在业务层裁剪 size 是不够的 ——
     * 任何一处漏判都会让 {@code size=100000} 打到数据库。这里在插件层再兜一道，
     * 即便业务代码写错，真正的 SQL LIMIT 也不会超限。</p>
     */
    @Bean
    public MybatisPlusInterceptor mybatisPlusInterceptor() {
        MybatisPlusInterceptor interceptor = new MybatisPlusInterceptor();
        PaginationInnerInterceptor pagination = new PaginationInnerInterceptor(DbType.MYSQL);
        pagination.setMaxLimit((long) PageResult.MAX_PAGE_SIZE);
        // 溢出总页数后不回到首页，而是返回空列表：语义更直白，前端不会"莫名回到第一页"
        pagination.setOverflow(false);
        interceptor.addInnerInterceptor(pagination);
        return interceptor;
    }

    /**
     * 密码哈希器：BCrypt strength=10（技术方案 §9「密码泄露」一行明确指定）。
     *
     * <p>为什么不调 strength：strength 越高越慢，10 是安全与单机性能的既定折中，
     * 属于契约参数，改动需要走 CR，不能在代码里"顺手优化"。</p>
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
