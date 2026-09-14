package com.hyforum.common.security;

import cn.dev33.satoken.SaManager;
import cn.dev33.satoken.stp.StpLogic;

import java.util.Map;

/**
 * 登录类型 → StpLogic 实例 的注册表（**本项目自建**，不是 Sa-Token 的 SPI）。
 *
 * <p>背景：Sa-Token 1.38 的 {@code SaManager} 里维护的是
 * {@code Map<String, StpLogic> stpLogicMap}，通过 {@code SaManager.putStpLogic(stpLogic)}
 * 注册、{@code SaManager.getStpLogic(loginType)} 取用；它<b>没有</b>
 * {@code StpLogicInterface} 这类 SPI 接口（该 API 在本版本不存在，已用 javap 核对过 jar）。</p>
 *
 * <p>那为什么还要这一层？因为这解决一个真实的隐患：
 * <b>如果两处各自 {@code new StpLogic("user")}</b>，就会得到两个不同的实例，
 * 它们虽然读写同一个 Redis key 前缀、行为上等价，但只要将来有人给其中一个实例设置了
 * 自定义配置（例如 {@code setTokenStyle}、{@code setTokenName}），
 * 就会出现"拦截器校验用的实例"与"业务登录用的实例"配置不一致的诡异故障。</p>
 *
 * <p>本类用一张静态表把<b>同一个实例</b>分发给所有调用方，并保证
 * {@link StpUserUtil#STP} / {@link StpAdminUtil#STP} 与
 * {@code SaManager} 里注册的是同一对象。</p>
 */
public final class StpLogicRegistry {

    /** loginType → 已登记的实例。用静态表是因为 Sa-Token 的静态调用可能在容器就绪前发生。 */
    private static final Map<String, StpLogic> REGISTRY = new java.util.concurrent.ConcurrentHashMap<>();

    private StpLogicRegistry() {
    }

    /**
     * 登记一个 StpLogic：同时放进本项目的表与 {@code SaManager}（后者让
     * {@code SaManager.getStpLogic(loginType)} 之类的框架内部调用也能找到它）。
     *
     * <p>幂等：重复登记同一 loginType 会覆盖为最后一次传入的实例，
     * 便于测试上下文重建时不会因为"已存在"而失败。</p>
     */
    public static void register(StpLogic stpLogic) {
        REGISTRY.put(stpLogic.getLoginType(), stpLogic);
        SaManager.putStpLogic(stpLogic);
    }

    /**
     * 按 loginType 取实例。
     *
     * @throws IllegalArgumentException 未登记的类型 —— 显式失败而不是返回 null，
     *         避免调用方拿到 null 后 NPE 在一个无关的地方爆出来
     */
    public static StpLogic get(String loginType) {
        StpLogic stpLogic = REGISTRY.get(loginType);
        if (stpLogic == null) {
            throw new IllegalArgumentException("未注册的登录类型：" + loginType);
        }
        return stpLogic;
    }

    /** 已登记的登录类型数量（供架构/健康检查使用）。 */
    public static int registeredCount() {
        return REGISTRY.size();
    }
}
