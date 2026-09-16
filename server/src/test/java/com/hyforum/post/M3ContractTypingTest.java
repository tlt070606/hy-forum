package com.hyforum.post;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.restassured.RestAssured;
import io.restassured.response.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 契约具名化（<b>CR-005</b>）：{@code GET /api/auth/captcha} 与 {@code GET /api/auth/register-mode}
 * 的 {@code data} 在契约里原本是 {@code Record<string, any>}（{@code ApiResponseMapStringObject}），
 * <b>字段名没有任何静态声明</b> —— 前端因此被迫加了一层「人工窄化类型 + 运行时校验」的临时层，
 * 并在注释里写明"这是契约不足，正式做法是提 CR"。
 *
 * <p>本任务批准并实施了这次契约变更（见 docs/agents/工作计划.md §3 CR-005）：</p>
 * <ul>
 *   <li>{@code captcha} → 具名 {@code CaptchaVO}（字段 {@code uuid}、{@code base64Image}）；</li>
 *   <li>{@code register-mode} → {@code RegisterModeVO}，其 {@code mode} 带取值约束
 *       {@code open / invite / closed}（§8.8）。</li>
 * </ul>
 *
 * <h2>为什么断言的是 {@code /v3/api-docs}，而不是 Java 类</h2>
 * <p>CR-005 的实质是<b>契约变了</b>，而不是"Java 里多了个类"。契约的唯一事实来源是
 * 由注解导出的 {@code openapi.json}（L1 用 {@code scripts/export_openapi.ps1} 冻结）。
 * 所以这里直接读运行中的应用导出的接口文档，断言三件事：
 * ① 具名 schema 存在且字段齐全；② 两个接口的响应 schema 已经<b>不再是</b>
 * {@code ApiResponseMapStringObject}；③ {@code mode} 的枚举约束真的写进了契约。
 * 只断言 ① 是不够的 —— 类建出来了但接口还在返回 {@code Map} 的情况很常见。</p>
 *
 * <p>本用例断言的是<b>契约（注解导出结果）</b>，属 M3 新增、待 L1 登记进映射表。</p>
 */
@DisplayName("M3 · 契约具名化（CR-005）")
class M3ContractTypingTest extends M3ApiTestSupport {

    @Test
    void M3_captcha_and_register_mode_are_typed_in_openapi() throws Exception {
        Response docResponse = RestAssured.given().get("/v3/api-docs");
        assertThat(docResponse.statusCode())
                .as("/v3/api-docs 必须可访问（契约导出入口，scripts/export_openapi.ps1 依赖它）")
                .isEqualTo(200);

        Map<String, Object> doc = new ObjectMapper()
                .readValue(docResponse.asString(), new TypeReference<Map<String, Object>>() {
                });
        Map<String, Object> schemas = asMap(navigate(doc, "components", "schemas"), "components.schemas");

        // ---------- ① captcha：具名 CaptchaVO，字段 uuid + base64Image ----------
        assertThat(schemas)
                .as("CR-005：captcha 必须导出具名 schema CaptchaVO。实际 schema 清单：%s", schemas.keySet())
                .containsKey("CaptchaVO");
        Map<String, Object> captchaVo = asMap(schemas.get("CaptchaVO"), "CaptchaVO");
        Map<String, Object> captchaProps = asMap(captchaVo.get("properties"), "CaptchaVO.properties");
        assertThat(captchaProps)
                .as("§6.2 规定验证码返回 {uuid, base64Image}，具名 VO 必须把字段声明出来")
                .containsKeys("uuid", "base64Image");

        // ---------- ② 两个接口的响应 schema 不再是 Record<string, any> ----------
        assertThat(responseSchemaName(doc, "/api/auth/captcha"))
                .as("CR-005：captcha 的响应必须是具名类型，而不是 ApiResponseMapStringObject")
                .contains("CaptchaVO")
                .doesNotContain("MapStringObject");
        assertThat(responseSchemaName(doc, "/api/auth/register-mode"))
                .as("CR-005：register-mode 的响应必须是具名类型，而不是 ApiResponseMapStringObject")
                .contains("RegisterModeVO")
                .doesNotContain("MapStringObject");

        // ---------- ③ register-mode：mode 字段带取值约束 open / invite / closed ----------
        Map<String, Object> registerModeVo = asMap(schemas.get("RegisterModeVO"), "RegisterModeVO");
        Map<String, Object> modeSchema = resolveProperty(doc, registerModeVo, "mode");
        Object enumValues = modeSchema.get("enum");
        assertThat(enumValues)
                .as("§8.8 的三种注册模式必须体现为契约里的枚举约束（前端据此做穷尽分支），"
                        + "实际 mode 的 schema：%s", modeSchema)
                .isNotNull();
        assertThat(asStringList(enumValues))
                .containsExactlyInAnyOrder("open", "invite", "closed");
    }

    /**
     * 本任务新增用例：{@code M3_all_m3_endpoints_are_annotated_in_openapi}
     * —— 证明第一交付段的 7 个端点<b>真的被注解导出到了契约里</b>。
     *
     * <h2>为什么这条值得单独存在</h2>
     * <p>「我实现了接口」与「契约里有这个接口」是两件事：前者靠接口测试就能证明，
     * 后者要等 L1 用 {@code scripts/export_openapi.ps1} 重新导出才会暴露问题
     * —— 一旦某个端点漏了注解（或注解写在私有方法上），导出的契约里就没有它，
     * <b>前端会拿到一份"看起来正常但少了一个接口"的契约</b>，
     * 而这类缺失在代码评审里极难用肉眼发现。</p>
     *
     * <p>断言口径：路径存在 + HTTP 方法齐全（{@code /api/posts} 必须同时有 get 与 post，
     * {@code /api/posts/{id}} 必须同时有 get／put／delete）。</p>
     *
     * <p>第二交付段的 {@code /api/oss/signature}、{@code /api/oss/callback} <b>刻意不在这里断言</b>：
     * 它们归第二交付段，现在写"必须不存在"会在第二段开工时立刻变成一条无意义的红。</p>
     */
    @Test
    void M3_all_m3_endpoints_are_annotated_in_openapi() throws Exception {
        Response docResponse = RestAssured.given().get("/v3/api-docs");
        assertThat(docResponse.statusCode())
                .as("/v3/api-docs 必须可访问（契约导出入口）")
                .isEqualTo(200);

        Map<String, Object> doc = new ObjectMapper()
                .readValue(docResponse.asString(), new TypeReference<Map<String, Object>>() {
                });
        Map<String, Object> paths = asMap(navigate(doc, "paths"), "paths");

        assertThat(paths.keySet())
                .as("M3 第一交付段的端点必须都出现在契约里（注解缺失 = 前端拿不到这个接口）。实际路径：%s",
                        paths.keySet())
                .contains("/api/boards", "/api/posts", "/api/posts/search", "/api/posts/{id}");

        assertThat(asMap(paths.get("/api/boards"), "/api/boards").keySet())
                .as("版块列表只有 GET（§6.4）")
                .containsExactly("get");
        assertThat(asMap(paths.get("/api/posts"), "/api/posts").keySet())
                .as("帖子集合：列表 GET + 发帖 POST（§6.5）")
                .containsExactlyInAnyOrder("get", "post");
        assertThat(asMap(paths.get("/api/posts/{id}"), "/api/posts/{id}").keySet())
                .as("单帖：详情 GET + 改帖 PUT + 删帖 DELETE（§6.5）")
                .containsExactlyInAnyOrder("get", "put", "delete");
        assertThat(asMap(paths.get("/api/posts/search"), "/api/posts/search").keySet())
                .as("搜索只有 GET（§6.5）")
                .containsExactly("get");
    }

    // ==================================================================
    // 在 OpenAPI 文档里导航的小工具（只读，不改任何东西）
    // ==================================================================
    /** 按 key 逐层读取嵌套 Map。 */
    @SuppressWarnings("unchecked")
    private static Object navigate(Map<String, Object> root, String... keys) {
        Object current = root;
        StringBuilder path = new StringBuilder();
        for (String key : keys) {
            path.append('/').append(key);
            if (!(current instanceof Map<?, ?> map)) {
                throw new AssertionError("OpenAPI 文档结构不符合预期：" + path + " 处不是对象，实际：" + current);
            }
            current = ((Map<String, Object>) map).get(key);
            if (current == null) {
                throw new AssertionError("OpenAPI 文档里缺少节点：" + path);
            }
        }
        return current;
    }

    private static Map<String, Object> asMap(Object value, String what) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new AssertionError(what + " 必须是对象，实际：" + value);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) map;
        return result;
    }

    private static List<String> asStringList(Object value) {
        if (!(value instanceof List<?> list)) {
            throw new AssertionError("期望一个数组，实际：" + value);
        }
        return list.stream().map(String::valueOf).toList();
    }

    /**
     * 取某个路径 200 响应体的 schema 名字。
     *
     * <p>契约只对 {@code $ref} 形态做断言（springdoc 对 {@code ApiResponse<T>} 一律产出
     * {@code ApiResponseXxxVO} 的具名包装），因此这里直接读 {@code $ref} 的最后一段。</p>
     */
    @SuppressWarnings("unchecked")
    private static String responseSchemaName(Map<String, Object> doc, String path) {
        Map<String, Object> pathItem = asMap(navigate(doc, "paths", path), "paths" + path);
        // 认证接口都是 GET（本用例只关心这两个），故直接取 get
        Map<String, Object> operation = asMap(pathItem.get("get"), path + ".get");
        Map<String, Object> responses = asMap(operation.get("responses"), path + ".responses");
        Object success = responses.get("200");
        if (success == null) {
            throw new AssertionError("路径 " + path + " 没有 200 响应，实际：" + responses.keySet());
        }
        Map<String, Object> content = asMap(asMap(success, "responses.200").get("content"),
                "responses.200.content");
        Map<String, Object> mediaType = asMap(content.values().iterator().next(), "content 的第一项");
        Map<String, Object> schema = asMap(mediaType.get("schema"), "content.schema");
        Object ref = schema.get("$ref");
        if (ref == null) {
            throw new AssertionError("路径 " + path + " 的响应 schema 不是具名 $ref（说明它仍是匿名形状）：" + schema);
        }
        String refText = String.valueOf(ref);
        return refText.substring(refText.lastIndexOf('/') + 1);
    }

    /**
     * 解析某个属性最终的 schema：属性可能是内联的，也可能是指向具名组件的 {@code $ref}。
     *
     * <p>两种形态都必须支持 —— springdoc 对嵌套枚举有时内联、有时抽成独立组件，
     * 用例写死其中一种就会因为"版本升级换了产出形态"而红。</p>
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> resolveProperty(Map<String, Object> doc,
                                                       Map<String, Object> owner,
                                                       String property) {
        Map<String, Object> properties = asMap(owner.get("properties"), "properties");
        Map<String, Object> propertySchema = asMap(properties.get(property),
                "缺少属性 " + property + "，实际属性：" + properties.keySet());
        Object ref = propertySchema.get("$ref");
        if (ref == null) {
            return propertySchema;
        }
        String refText = String.valueOf(ref);
        String name = refText.substring(refText.lastIndexOf('/') + 1);
        Map<String, Object> schemas = asMap(navigate(doc, "components", "schemas"), "components.schemas");
        return asMap(schemas.get(name), "components.schemas." + name);
    }
}
