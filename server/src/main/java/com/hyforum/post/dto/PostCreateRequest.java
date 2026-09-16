package com.hyforum.post.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 发帖请求体（docs/技术方案.md §6.5 {@code POST /api/posts}）。
 *
 * <p>字段口径逐条来自契约：资源版块需要 {@code diskType}、{@code diskUrl}，
 * {@code diskCode} 可选。图片按 §8.4 第 4 条<b>只提交 URL 列表</b>
 * （字节流由前端直传 OSS，不经过后端 —— 铁律 8）。</p>
 *
 * <h2>为什么图片列表不加 {@code @Size(max = 9)}</h2>
 * <p>「单帖图片 ≤ 9」这条规则放在 Service 里判定（{@code PostService}），
 * 而不是 Bean Validation：</p>
 * <ul>
 *   <li>它需要的是<b>一个清晰的业务提示</b>（"单帖最多 9 张图"），
 *       而 {@code @Valid} 失败只给出"images 数量超出限制"这类字段级文案；</li>
 *   <li>更重要的是它与"图片 URL 归属校验"必须<b>一起</b>决定"整条请求是否被拒"，
 *       两者分开会让维护者以为改一处就够了（实测过：只改 DTO 上的注解，
 *       Service 里的 9 张上限仍在生效，行为看起来"时对时错"）。</li>
 * </ul>
 * <p>校验顺序上的一个已知后果（如实记录）：{@code @Valid} 发生在 Controller 方法体之前，
 * 所以<b>字段级非法</b>（标题为空、boardId 缺失）的请求不会消耗发帖限流配额，
 * 而"图片过多"这类业务级非法请求会消耗 —— 这是刻意的取舍：
 * 让刷非法请求不消耗配额，等于给了一条绕过限流的刷量路径。</p>
 *
 * @param boardId  版块 id（必填，必须存在且启用）
 * @param title    标题（必填，≤100 字）
 * @param content  正文（可选，≤10000 字；TEXT 列）
 * @param images   图片 URL 列表（可空；元素必须是本项目 OSS 目录下的地址）
 * @param diskType 网盘类型 1 百度 / 2 阿里 / 3 夸克 / 4 天翼 / 5 迅雷 / 6 其他；<b>仅资源版块</b>
 * @param diskUrl  网盘分享链接（可含 {@code pwd}，后端会归一化）；<b>仅资源版块</b>
 * @param diskCode 提取码，可选（阿里云盘/夸克无提取码机制）
 */
@Schema(name = "PostCreateRequest", description = "发帖请求体")
public record PostCreateRequest(
        @Schema(description = "版块 id", example = "2")
        @NotNull(message = "不能为空")
        @Positive(message = "必须是正整数")
        Long boardId,

        @Schema(description = "标题", example = "分享一套 Java 学习资料")
        @NotBlank(message = "不能为空")
        @Size(max = 100, message = "不能超过 100 字")
        String title,

        @Schema(description = "正文")
        @Size(max = 10000, message = "不能超过 10000 字")
        String content,

        @Schema(description = "图片 URL 列表（必须是本项目 OSS 目录下的地址，最多 9 张）")
        List<String> images,

        @Schema(description = "网盘类型：1 百度 / 2 阿里 / 3 夸克 / 4 天翼 / 5 迅雷 / 6 其他")
        Integer diskType,

        @Schema(description = "网盘分享链接，可带 pwd 参数（后端会拆出提取码并去掉它）")
        @Size(max = 500, message = "不能超过 500 字符")
        String diskUrl,

        @Schema(description = "提取码，可选")
        @Size(max = 20, message = "不能超过 20 字符")
        String diskCode) {
}
