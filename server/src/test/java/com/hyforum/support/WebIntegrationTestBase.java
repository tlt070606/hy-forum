package com.hyforum.support;

import io.restassured.RestAssured;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

/**
 * 需要**真 HTTP 端口**的集成测试基类（接口测试：REST Assured）。
 *
 * <h2>为什么单独一层</h2>
 * {@link IntegrationTestBase} 用的是 Spring Boot 默认的 MOCK web 环境
 * （不起 Servlet 容器，只做 SQL/Redis 断言时更快）。
 * 一旦要验证「统一响应体、错误码、鉴权拦截器、认证过滤器链」这类**跨切面**行为，
 * 就必须用真实端口跑完整 filter 链 —— 这是 {@code M1_*}/{@code SEC_*}/{@code M3_*} 等
 * 接口测试的共同需求，故做成共享基类，避免每个模块各写一套。
 *
 * <p>继承后自动获得：{@link IntegrationTestBase} 的测试库防线与清表机制、
 * {@code jdbcTemplate}/{@code stringRedisTemplate}/{@code fixtures}，
 * 以及本类配置好的 REST Assured（{@code baseURI} + 随机端口）。</p>
 *
 * <pre>{@code
 * class PostApiTest extends WebIntegrationTestBase {
 *     @Test void M3_xxx() {
 *         given().contentType(JSON).body(...).post("/api/posts").then().statusCode(200);
 *     }
 * }
 * }</pre>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class WebIntegrationTestBase extends IntegrationTestBase {

    /** 随机端口（RANDOM_PORT），避免与本地开发中的 8080 冲突 */
    @LocalServerPort
    protected int port;

    @BeforeEach
    void configureRestAssured() {
        RestAssured.baseURI = "http://127.0.0.1";
        RestAssured.port = port;
        // 断言失败时把完整请求/响应打出来 —— 接口测试看不到报文等于盲测
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }

    @AfterEach
    void resetRestAssured() {
        RestAssured.reset();   // 静态状态：不清会污染同一 JVM 里的后续测试类
    }
}
