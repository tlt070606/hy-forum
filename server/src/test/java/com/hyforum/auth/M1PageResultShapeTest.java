package com.hyforum.auth;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.module.paramnames.ParameterNamesModule;
import com.hyforum.common.api.PageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分页结构形状（docs/技术方案.md §6.1）。
 *
 * <p>对应验收项：{@code M1_response_envelope_shape}（该验收项的断言要点是
 * "「{@code {code,message,data}}、分页结构」两部分；HTTP 报文侧在
 * {@link M1ResponseEnvelopeTest}，本类负责分页结构的<b>形状</b>部分）。</p>
 *
 * <p><b>为什么 M1 就要把分页形状测住</b>：M1 没有任何分页接口（版块/帖子/评论都在 M3/M4），
 * 但分页是跨模块的公共形状。若不在这里把形状定死并测住，M3、M4、M5 会各自造一套
 * {@code {rows,count}} / {@code {items,totalPages}} 之类的私有结构，前端就得为每个列表
 * 写一份适配代码 —— 这正是"临时约定"最典型的扩散方式。</p>
 *
 * <p><b>为什么是单元测试而不是接口测试</b>：M1 没有可分页的接口可打；
 * 而这里要断言的恰恰是"契约规定的键名与键序"，与数据来源无关。
 * 放在单元层还有一个实际好处：这条断言不依赖 MySQL/Redis，
 * 不会因为环境不可用而红（分页形状与数据库无关）。</p>
 */
@DisplayName("M1 · 分页结构形状")
class M1PageResultShapeTest {

    @Test
    void M1_response_envelope_shape_pagination() {
        PageResult<String> page = PageResult.of(List.of("a", "b"), 42L, 2, 20);

        String json = toJson(page);
        assertThat(json)
                .as("分页结构必须严格是 §6.1 的 {list,total,page,size}（键名与顺序都固定），实际：%s", json)
                .isEqualTo("{\"list\":[\"a\",\"b\"],\"total\":42,\"page\":2,\"size\":20}");

        // 分页硬上限 20（docs/ops/deployment.md §5，验收项 M3_page_size_hard_cap_20）：
        // 形状定义的地方同时收敛上限，避免每个模块各判一次
        assertThat(PageResult.normalizeSize(100))
                .as("size=100 必须被裁剪到硬上限 20").isEqualTo(20);
        assertThat(PageResult.normalizeSize(0))
                .as("size<=0 必须归一到默认 20").isEqualTo(20);
        assertThat(PageResult.normalizePage(0))
                .as("page<=0 必须归一到第 1 页").isEqualTo(1);
        assertThat(PageResult.normalizePage(3))
                .as("合法页码不得被改动").isEqualTo(3);
    }

    /**
     * 用与 Spring Boot 实际使用<b>同一套配置</b>的 ObjectMapper 序列化。
     *
     * <p>为什么不用 {@code new ObjectMapper()}：记录（record）的序列化依赖
     * {@code ParameterNamesModule}；Spring Boot 的 ObjectMapper 默认装配了它，
     * 而裸的 ObjectMapper 会在 record 上抛 {@code InvalidDefinitionException}。
     * 测试必须与运行时的序列化口径一致，否则断言的是一个"生产不会出现"的结果。</p>
     */
    private static String toJson(Object value) {
        try {
            return JsonMapper.builder()
                    .addModule(new ParameterNamesModule())
                    .build()
                    .writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("序列化失败：" + value, ex);
        }
    }
}
