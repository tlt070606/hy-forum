package com.hyforum.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hyforum.common.api.ErrorCode;
import com.hyforum.common.exception.BizException;
import com.hyforum.domain.admin.entity.AdminOperationLog;
import com.hyforum.domain.config.entity.SysConfig;
import com.hyforum.domain.config.mapper.SysConfigMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * 系统配置管理（M6）：注册模式切换（docs/技术方案.md §8.8 + §6.11）。
 *
 * <h2>为什么不复用 {@code auth} 包的 {@code RegisterModeService}</h2>
 * <p>铁律 3：{@code admin → auth} 是业务包依赖，ArchUnit 会红。
 * {@code RegisterModeService} 的<b>读路径</b>（含"行缺失按 open 兜底"的语义）继续由
 * auth 包独占 —— 前台注册/查询走它；<b>写路径</b>在这里用 domain 的
 * {@code SysConfigMapper} 直接一条条件 UPDATE 完成（{@code config_key} 唯一，天然幂等）。
 * 两条路径写的是同一行 {@code sys_config.register_mode}，"切换后立即生效"由
 * auth 侧"不缓存、每次直查"保证（见 {@code RegisterModeService} 类注释）。</p>
 */
@Service
public class AdminConfigService {

    private static final Logger log = LoggerFactory.getLogger(AdminConfigService.class);

    /** 动作编码（schema 表 16 的 action 列注释枚举之一）。 */
    public static final String ACTION_REGISTER_MODE = "REGISTER_MODE";

    /** 配置键名（与 auth 包 RegisterModeService.CONFIG_KEY 同一行数据，字面量互为指认）。 */
    public static final String CONFIG_KEY = "register_mode";

    /** 合法取值（与 {@code RegisterMode} 的 open/invite/closed 一致；admin 包不得 import 它）。 */
    private static final Set<String> ALLOWED_MODES = Set.of("open", "invite", "closed");

    private final SysConfigMapper sysConfigMapper;
    private final AdminOperationLogger operationLogger;

    public AdminConfigService(SysConfigMapper sysConfigMapper, AdminOperationLogger operationLogger) {
        this.sysConfigMapper = sysConfigMapper;
        this.operationLogger = operationLogger;
    }

    /**
     * 切换注册模式（§6.11 {@code PUT /api/admin/configs/register-mode}）。
     *
     * <p>切换立即生效（auth 侧每次直查库，无缓存窗口）。留痕记 {@code mode:A->B}；
     * 与目标相同的重复切换也留痕（detail 可见 {@code ->} 相同值，审计时是"确认过一次"的痕迹）。</p>
     *
     * @param adminId 操作管理员 id
     * @param mode    目标模式：open / invite / closed
     * @param ip      操作来源 IP
     * @return 留痕行 id
     */
    @Transactional
    public long switchRegisterMode(long adminId, String mode, String ip) {
        if (mode == null || !ALLOWED_MODES.contains(mode.trim())) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "mode 只支持 open / invite / closed，收到：" + mode);
        }
        String target = mode.trim();

        SysConfig config = sysConfigMapper.selectOne(
                Wrappers.<SysConfig>lambdaQuery().eq(SysConfig::getConfigKey, CONFIG_KEY));
        if (config == null || config.getConfigValue() == null) {
            // 与 auth 侧"缺失按 open 兜底"不同：写路径对缺失行直接报错 ——
            // 兜底读会掩盖"库没按 M0 初始化"的问题，写路径没有理由跟着兜
            throw new BizException(ErrorCode.NOT_FOUND,
                    "sys_config 中缺少 " + CONFIG_KEY + " 配置行，请先执行 docs/db/seed.sql");
        }
        String previous = config.getConfigValue();

        int affected = sysConfigMapper.update(null, Wrappers.<SysConfig>lambdaUpdate()
                .eq(SysConfig::getConfigKey, CONFIG_KEY)
                .set(SysConfig::getConfigValue, target));
        if (affected == 0) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "注册模式切换失败（更新影响 0 行）");
        }

        long logId = operationLogger.log(adminId, ACTION_REGISTER_MODE,
                AdminOperationLog.TARGET_CONFIG, null, null,
                "register_mode:" + previous + "->" + target, ip);
        log.info("注册模式切换：adminId={} {}->{}", adminId, previous, target);
        return logId;
    }
}
