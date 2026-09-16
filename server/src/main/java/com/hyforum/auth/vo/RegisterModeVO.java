package com.hyforum.auth.vo;

import com.hyforum.auth.mode.RegisterMode;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 注册模式响应（<b>CR-005</b>，docs/技术方案.md §6.2／§8.8）。
 *
 * <h2>为什么 {@code mode} 是枚举而不是 {@code String}</h2>
 * <p>CR-005 的要求不只是"给个具名类型"，还有"<b>带取值约束</b>"。
 * 用 {@code String} 只能表达"这里是个字符串"，导出的契约里就没有枚举约束，
 * 前端的穷尽分支（{@code open}／{@code invite}／{@code closed}）拿不到编译期保障 ——
 * 值域一旦变化（例如将来加一个新的注册模式），前端是运行时才崩。</p>
 * <p>因此这里用嵌套枚举 {@link RegisterModeValue}，springdoc 会把它导出成
 * 带 {@code enum: [open, invite, closed]} 的 schema。枚举常量刻意写成小写：
 * 它们就是契约里的字面值，这样"契约值"与"序列化结果"在代码上是同一个东西，
 * 不会出现"常量叫 OPEN、契约里写 open"这种两处维护。</p>
 *
 * @param mode           当前注册模式（open / invite / closed）
 * @param inviteRequired 是否需要邀请码（前端渲染条件更直观的冗余布尔位；
 *                       值本身仍以 {@code mode} 为唯一事实来源）
 */
@Schema(name = "RegisterModeVO", description = "注册模式：open / invite / closed")
public record RegisterModeVO(RegisterModeValue mode, Boolean inviteRequired) {

    /** 契约里的注册模式字面值（§8.8）。 */
    @Schema(name = "RegisterModeValue", description = "open 开放注册 / invite 邀请码注册 / closed 关闭注册")
    public enum RegisterModeValue {

        /** 任何人可注册（需图形验证码）。 */
        open,

        /** 必须提供有效邀请码。 */
        invite,

        /** 完全关闭注册。 */
        closed;

        /**
         * 由 {@link RegisterMode} 映射过来。
         *
         * <p>两个枚举刻意不合并：{@code RegisterMode} 是<b>业务语义</b>（它的 {@code parse}
         * 带容错与 fail-fast 规则），本枚举是<b>契约字面值</b>（要出现在 openapi.json 里）。
         * 把业务枚举直接当返回值，会让"改一处业务校验"意外改变契约。</p>
         */
        public static RegisterModeValue from(RegisterMode mode) {
            return switch (mode) {
                case OPEN -> open;
                case INVITE -> invite;
                case CLOSED -> closed;
            };
        }
    }
}
