package com.hyforum.admin.service;

import com.hyforum.domain.admin.entity.AdminOperationLog;
import com.hyforum.domain.admin.mapper.AdminOperationLogMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * 管理操作留痕的<b>统一写入口</b>（M6 起，全部后台写动作经此落痕）。
 *
 * <h2>为什么抽成组件而不是各服务自己 insert</h2>
 * <p>M5 的 {@code CommentAuditService} 里有一段私有 {@code writeLog}，注释写着
 * "抽成私有方法是为了让『留痕字段填全』只有一处实现"。M6 又要新增四类留痕动作
 * （帖子 / 用户 / 配置 / 邀请码），如果每处各写一遍"new AdminOperationLog + 填八个字段"，
 * 必然有一处漏 {@code reason} 或漏 {@code ip} —— 而漏 {@code reason} 的后果是
 * 合规审计时无法追溯处置依据（C9）。因此升级为<b>包级共享组件</b>。</p>
 *
 * <h2>事务红线不变（§6.11）</h2>
 * <p>本组件<b>不开启自己的事务</b>：它只做 insert，跟着调用方的事务走 ——
 * 留痕失败 → 整个业务事务回滚，不允许出现"已处置但无记录"。
 * 调用方（各 Admin*Service）的写方法必须标 {@code @Transactional}。</p>
 */
@Component
public class AdminOperationLogger {

    private final AdminOperationLogMapper mapper;

    public AdminOperationLogger(AdminOperationLogMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * 写一条留痕（随调用方事务提交）。
     *
     * @param adminId    操作管理员 id（由 Controller 从后台登录态取，不来自请求体）
     * @param action     动作编码（如 {@code POST_STATUS}，与 schema 列注释枚举一致）
     * @param targetType 目标类型（{@link AdminOperationLog} 的 TARGET_* 常量）
     * @param targetId   对象主键（无具体对象传 null，如注册模式切换）
     * @param reason     处置理由；屏蔽/封禁类动作调用方必须先校验非空（C9）
     * @param detail     变更摘要（如 {@code status:1->2}、{@code register_mode:open->invite}）
     * @param ip         操作来源 IP（可为 null）
     * @return 留痕行 id（测试用它断言"留了痕"）
     */
    public long log(long adminId, String action, int targetType, Long targetId,
                    String reason, String detail, String ip) {
        AdminOperationLog entry = new AdminOperationLog();
        entry.setAdminId(adminId);
        entry.setAction(action);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);
        entry.setReason(reason);
        entry.setDetail(detail);
        entry.setIp(ip);
        entry.setCreatedAt(LocalDateTime.now());
        mapper.insert(entry);
        return entry.getId();
    }
}
