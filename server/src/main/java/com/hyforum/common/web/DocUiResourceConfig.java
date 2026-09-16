package com.hyforum.common.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 为 Knife4j 的文档页单独开一条静态资源通道。
 *
 * <h3>为什么需要这个类（2026-09-16 实测定位，见 H8）</h3>
 *
 * <p>{@code application.yml} 里刻意设了两条：
 * <pre>
 *   spring.mvc.throw-exception-if-no-handler-found: true
 *   spring.web.resources.add-mappings: false
 * </pre>
 * 目的是让**未匹配的路径**抛 {@code NoHandlerFoundException}，由
 * {@code GlobalExceptionHandler} 返回契约 §6.1 的统一 404 响应体。
 * <b>这是刻意设计，不能整体打开 {@code add-mappings}</b> —— 打开后未知路径会走
 * Spring 默认的静态资源处理，不再抛异常，契约里的统一 404 就失效了。
 *
 * <p>但 Knife4j 的文档页 {@code doc.html} 是**静态资源**
 * （位于 {@code META-INF/resources/doc.html}，JS/CSS 在 {@code webjars/} 下），
 * <b>不是 controller 映射</b>。静态映射被关闭后它就没有 handler，于是 {@code /doc.html} 返回 404。
 *
 * <p><b>为什么 Swagger UI 却正常</b>：springdoc 自己注册了 {@code /swagger-ui/**} 的
 * 资源处理器，不依赖 Boot 的静态映射；Knife4j 没有。<b>实测证据</b>
 * （{@code add-mappings=false} + {@code knife4j.enable=true} 下逐个探测）：
 * <pre>
 *   /swagger-ui/index.html            200   ← springdoc 自己的处理器
 *   /doc.html                         404   ← 静态映射被关
 *   /webjars/js/app.&lt;hash&gt;.js        404   ← 同上（Knife4j 的资源在 webjars/ 下）
 * </pre>
 *
 * <h3>所以这里只补最小的一块</h3>
 *
 * <p>仅为这两个路径前缀注册资源处理器，<b>其余一切路径的 404 行为一字不变</b>
 * （仍然抛 {@code NoHandlerFoundException} → 统一响应体）。
 *
 * <p>注意：{@code add-mappings: false} 只关掉 Boot **自动配置**的静态映射；
 * 通过 {@code WebMvcConfigurer} **显式注册**的处理器照常生效 ——
 * 这一点由「springdoc 的 {@code /swagger-ui/**} 在同样配置下仍返回 200」间接证明。
 *
 * <p>{@code /webjars/**} 指向的是 {@code classpath:/META-INF/resources/webjars/}，
 * 而 Knife4j 与 springdoc 的 webjars 都在这个位置下，故一条映射同时够用。
 */
@Configuration
public class DocUiResourceConfig implements WebMvcConfigurer {

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // Knife4j 的页面外壳。指向 META-INF/resources/ 而非其 webjars/ 子目录，
        // 因为 doc.html 就放在 META-INF/resources/ 根下。
        registry.addResourceHandler("/doc.html")
                .addResourceLocations("classpath:/META-INF/resources/");

        // Knife4j 页面引用的 JS/CSS/字体/图片。文件名带内容哈希，故必须整目录映射。
        registry.addResourceHandler("/webjars/**")
                .addResourceLocations("classpath:/META-INF/resources/webjars/");
    }
}
