package com.hyforum.auth.mode;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hyforum.domain.config.entity.SysConfig;
import com.hyforum.domain.config.mapper.SysConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 注册模式读取与切换（docs/技术方案.md §8.8）。
 *
 * <p><b>数据驱动是硬要求</b>：模式值只存在 {@code sys_config.register_mode}，
 * 代码里不得出现任何"环境/档位"判断（ADR-0010），也不得缓存到进程启动时读一次 ——
 * §8.8 明确要求"切换后行为立即变化，无需重启"。</p>
 *
 * <p><b>缓存策略：不缓存。</b>本条是任务书 §5 DoD 第 5 条点名要说明的点：</p>
 * <ul>
 *   <li>读取对象是{@code sys_config} 里的一行（{@code uk_config_key} 唯一索引命中的等值查询），
 *       代价约等于一次点查；而注册是低频操作（一天几十次），频次最高的
 *       {@code GET /api/auth/register-mode} 也只是前端进注册页时读一次；</li>
 *   <li>若引入缓存，就必须额外设计失效路径（后台写配置 → 通知各实例失效），
 *       在"单机 + 低频"的场景下这是纯粹的复杂度与故障面；</li>
 *   <li>因此本实现选择<b>每次直接查库</b>：切换后立即生效，没有任何最终一致窗口，
 *       行为可预测，测试也不需要"等待缓存过期"这种不确定性。</li>
 * </ul>
 * <p>若将来该查询真的成为热点（例如注册量级上来），再加缓存时必须同时提供
 * 「管理后台写配置后主动失效」的路径，并把失效时刻写进 §8.8 的说明。</p>
 */
@Service
public class RegisterModeService {

    private static final Logger log = LoggerFactory.getLogger(RegisterModeService.class);

    /** 配置键名（技术方案 §8.8 与 docs/db/seed.sql 一致）。 */
    public static final String CONFIG_KEY = "register_mode";

    private final SysConfigMapper sysConfigMapper;

    public RegisterModeService(SysConfigMapper sysConfigMapper) {
        this.sysConfigMapper = sysConfigMapper;
    }

    /**
     * 读取当前注册模式。
     *
     * <p>配置行缺失时返回 {@link RegisterMode#OPEN}：这是 §8.8 表格里标注的默认值，
     * 也是 {@code seed.sql} 的基线值。但会打一条 WARN —— 缺失意味着库没按 M0 初始化，
     * 属于需要被发现的异常状态。</p>
     */
    @Transactional(readOnly = true)
    public RegisterMode currentMode() {
        SysConfig config = sysConfigMapper.selectOne(
                Wrappers.<SysConfig>lambdaQuery().eq(SysConfig::getConfigKey, CONFIG_KEY));
        if (config == null || config.getConfigValue() == null) {
            log.warn("sys_config 中缺少 {} 配置项，按契约默认值 open 处理；请检查 M0 的 seed.sql 是否已执行",
                    CONFIG_KEY);
            return RegisterMode.OPEN;
        }
        return RegisterMode.parse(config.getConfigValue());
    }

    /**
     * 切换注册模式（供管理后台调用，M6 会挂到 {@code PUT /api/admin/configs} 上）。
     *
     * <p>为什么 M1 就提供写入口：验收项 {@code M1_register_mode_open_allows} /
     * {@code ..._invite_requires_code} / {@code ..._closed_rejects} 需要真的切换模式来验证
     * "行为随数据变化"，而这三个用例是 M1 的 DoD。写入口本身属于 M6 的后台接口范畴，
     * 此处只提供 Service 能力，不对外暴露 HTTP 端点（后台接口由 M6 实现）。</p>
     *
     * <p>更新条件带 {@code config_key}，不做"先查再改"：目标是单行且键唯一，
     * 一条 UPDATE 就能完成，且天然幂等。</p>
     *
     * @param mode 目标模式
     * @return 影响行数（=0 表示配置行不存在，调用方需先初始化）
     */
    @Transactional
    public int switchMode(RegisterMode mode) {
        SysConfig update = new SysConfig();
        update.setConfigValue(mode.value());
        int affected = sysConfigMapper.update(update,
                Wrappers.<SysConfig>lambdaUpdate().eq(SysConfig::getConfigKey, CONFIG_KEY));
        log.info("注册模式切换为 {}（影响行数={}）", mode.value(), affected);
        return affected;
    }
}
