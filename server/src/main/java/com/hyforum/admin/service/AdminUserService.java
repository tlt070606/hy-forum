package com.hyforum.admin.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hyforum.common.api.ErrorCode;
import com.hyforum.common.api.PageResult;
import com.hyforum.common.exception.BizException;
import com.hyforum.common.security.StpUserUtil;
import com.hyforum.domain.admin.entity.AdminOperationLog;
import com.hyforum.domain.user.entity.User;
import com.hyforum.domain.user.mapper.UserMapper;
import com.hyforum.admin.vo.AdminUserVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 用户管理（M6）：搜索 / 封禁 / 解封（docs/技术方案.md §6.11）。
 *
 * <h2>封禁的语义是三件事在同一事务里</h2>
 * <ol>
 *   <li>{@code user.status} → 0：{@code AuthInterceptor} 每个请求都查状态
 *       （{@code AccountStatusChecker}），所以下一次请求立即被拒（1004），不等 token 过期；</li>
 *   <li><b>主动踢下线</b>：{@code StpUserUtil.STP.logout(userId)} 删除该用户全部 token ——
 *       只改状态的话，已登录用户的请求要到下一次进拦截器才被拒；踢掉则立刻断。
 *       两者叠加是"双保险"：即使 Redis 里残留了漏删的 token，状态检查也兜底；</li>
 *   <li>留痕（reason 必填，合规 C9）。</li>
 * </ol>
 *
 * <p><b>为什么踢下线不放进"封禁是否生效"的必要路径</b>：状态检查是每次请求的权威判定，
 * 踢 token 只是体验优化（省一次"发了请求才知道被拒"）。所以留痕与状态更新是事务主体，
 * 踢 token 失败不该回滚封禁 —— 它在事务内执行但即使 Redis 抖动，状态兜底仍在。
 * （Sa-Token 的 logout 按 loginId 删除是幂等的。）</p>
 */
@Service
public class AdminUserService {

    private static final Logger log = LoggerFactory.getLogger(AdminUserService.class);

    /** 动作编码（schema 表 16 的 action 列注释枚举之一）。 */
    public static final String ACTION_USER_BAN = "USER_BAN";
    public static final String ACTION_USER_UNBAN = "USER_UNBAN";

    /** 理由长度上限，与 {@code admin_operation_log.reason} 的 VARCHAR(200) 一致。 */
    private static final int MAX_REASON_LENGTH = 200;

    /** user.status：1 正常（schema 列注释）。 */
    private static final int USER_STATUS_ACTIVE = 1;
    /** user.status：0 封禁。 */
    private static final int USER_STATUS_BANNED = 0;

    private final UserMapper userMapper;
    private final AdminOperationLogger operationLogger;

    public AdminUserService(UserMapper userMapper, AdminOperationLogger operationLogger) {
        this.userMapper = userMapper;
        this.operationLogger = operationLogger;
    }

    /**
     * 后台用户列表（§6.11 {@code GET /api/admin/users}）：username / nickname 模糊搜索。
     *
     * <p>关键词转义 {@code LIKE} 通配符（与 {@code PostService.escapeLike} 同一做法），
     * 防止管理员搜 {@code %} 时把全表扫出来。</p>
     */
    @Transactional(readOnly = true)
    public PageResult<AdminUserVO> listUsers(String keyword, int page, int size) {
        int pageNo = PageResult.normalizePage(page);
        int pageSize = PageResult.normalizeSize(size);

        String escaped = escapeLike(keyword);
        IPage<User> result = userMapper.selectPage(new Page<>(pageNo, pageSize),
                Wrappers.<User>lambdaQuery()
                        .and(escaped != null, w -> w
                                .like(User::getUsername, escaped)
                                .or()
                                .like(User::getNickname, escaped))
                        .orderByDesc(User::getId));

        List<AdminUserVO> items = result.getRecords().stream()
                .map(user -> new AdminUserVO(
                        user.getId(),
                        user.getUsername(),
                        user.getNickname(),
                        user.getStatus() == null ? 1 : user.getStatus(),
                        user.getPostCount() == null ? 0 : user.getPostCount(),
                        user.getCreatedAt()))
                .toList();
        return PageResult.of(items, result.getTotal(), pageNo, pageSize);
    }

    /**
     * 封禁用户（§6.11 {@code PUT /api/admin/users/{id}/ban}）。
     *
     * @param adminId 操作管理员 id
     * @param userId  目标用户 id
     * @param reason  封禁理由（<b>必填</b>，合规 C9）
     * @param ip      操作来源 IP
     * @return 留痕行 id
     */
    @Transactional
    public long ban(long adminId, long userId, String reason, String ip) {
        return setStatus(adminId, userId, USER_STATUS_BANNED, reason, ACTION_USER_BAN, ip);
    }

    /**
     * 解封用户（{@code PUT /api/admin/users/{id}/unban}）：reason 可选（恢复性动作）。
     *
     * @return 留痕行 id
     */
    @Transactional
    public long unban(long adminId, long userId, String reason, String ip) {
        return setStatus(adminId, userId, USER_STATUS_ACTIVE, reason, ACTION_USER_UNBAN, ip);
    }

    private long setStatus(long adminId, long userId, int targetStatus,
                           String reason, String action, String ip) {
        boolean banning = targetStatus == USER_STATUS_BANNED;
        String normalizedReason = blankToNull(reason);
        if (banning && normalizedReason == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "封禁用户必须提供 reason（合规 C9：处置依据可追溯）");
        }
        if (normalizedReason != null && normalizedReason.length() > MAX_REASON_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "reason 不能超过 " + MAX_REASON_LENGTH + " 字符");
        }
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }

        int previous = user.getStatus() == null ? USER_STATUS_ACTIVE : user.getStatus();
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getStatus, targetStatus));

        if (banning) {
            // 踢下线该用户全部登录态（幂等）；状态检查是兜底权威，见类注释
            StpUserUtil.STP.logout(userId);
        }

        long logId = operationLogger.log(adminId, action,
                AdminOperationLog.TARGET_USER, userId, normalizedReason,
                "status:" + previous + "->" + targetStatus, ip);
        log.info("用户状态变更留痕：adminId={} userId={} {}，reason={}",
                adminId, userId, action, normalizedReason);
        return logId;
    }

    /**
     * 转义 {@code LIKE} 通配符（{@code \ % _}）。
     *
     * <p>与 {@code PostService} 的同名逻辑同一做法；刻意复制而不是抽到 common：
     * 一段 6 行的纯函数，为它建跨包依赖不划算（{@code AdminAuthController#clientIp} 的
     * 同一判断）。两处注释互为指认：改一处必须看另一处。</p>
     */
    private static String escapeLike(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return null;
        }
        String trimmed = keyword.trim();
        return trimmed.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
