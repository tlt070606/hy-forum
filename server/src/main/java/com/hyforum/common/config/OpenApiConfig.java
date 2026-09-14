package com.hyforum.common.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档元信息（knife4j / springdoc）。
 *
 * <p>为什么要把标题/版本写进代码而不是留在配置里：{@code openapi.json} 是接口契约的
 * 唯一事实来源、由 L1 冻结（docs/agents/工作计划.md §2）。若文档标题是默认的
 * {@code OpenAPI definition}、版本是 {@code v0}，冻结下来的契约文件就无法体现
 * "这是哪个项目的哪一版接口"，人工比对与归档都会失去依据 —— 契约的可追溯性依赖这些元信息。</p>
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI hyForumOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Hy论坛 API")
                .version("0.0.1")
                .description("""
                        Hy论坛 后端接口契约。

                        通用约定（docs/技术方案.md §6.1）：
                        - 统一响应体：`{code, message, data}`，`code=0` 表示成功；
                        - 分页响应：`data` 为 `{list, total, page, size}`，每页上限 20；
                        - 鉴权：请求头 `Authorization: {token}`（Sa-Token）；
                        - 前后台隔离：前台 `/api`、后台 `/api/admin` 使用两套独立登录态，互不通用；
                        - 错误码：0 成功 / 400 参数错误 / 401 未登录 / 403 无权限 / 404 资源不存在 /
                          429 请求过于频繁 / 1001 用户名已存在 / 1002 用户名或密码错误 /
                          1003 验证码错误 / 1004 账号已被封禁 / 2001 内容包含敏感词 / 2002 发帖过于频繁。

                        本文件由后端注解导出、由 L1 冻结，任何人不手工编辑。
                        """)
                .contact(new Contact().name("Hy论坛 后端"))
                .license(new License().name("Proprietary")));
    }
}
