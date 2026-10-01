package com.hyforum.admin.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hyforum.common.api.ErrorCode;
import com.hyforum.common.api.PageResult;
import com.hyforum.common.exception.BizException;
import com.hyforum.domain.admin.entity.AdminOperationLog;
import com.hyforum.domain.invite.entity.InviteCode;
import com.hyforum.domain.invite.mapper.InviteCodeMapper;
import com.hyforum.admin.vo.AdminInviteCodeVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/**
 * 邀请码管理（M6）：生成 / 列表（docs/技术方案.md §6.11、§8.8）。
 *
 * <h2>生成规则</h2>
 * <ul>
 *   <li><b>16 位、字母数字、剔除易混淆字符</b>（{@code I O 0 1}）：邀请码要被人抄进注册框，
 *       与 {@code CaptchaService} 剔除易混淆字符是同一理由；</li>
 *   <li>{@code SecureRandom}：邀请码是"谁持有哪些码"的准入凭据，不能可预测；</li>
 *   <li>撞 {@code uk_code} 重试（指数上概率趋近于零，防御性上限 5 次）；</li>
 *   <li>批量生成 N 条 = 一批 INSERT + <b>一条</b>留痕（detail 记 {@code count:N}）——
 *       留痕的对象是"这次生成动作"，不是每个码；逐码留痕只会把队列刷成噪音。</li>
 * </ul>
 */
@Service
public class AdminInviteService {

    private static final Logger log = LoggerFactory.getLogger(AdminInviteService.class);

    /** 动作编码（schema 表 16 的 action 列注释枚举之一）。 */
    public static final String ACTION_INVITE_CODE = "INVITE_CODE";

    /** 单次生成数量上限：批量是给运营发码用的，50 张足够一次活动。 */
    private static final int MAX_BATCH = 50;

    /** 过期天数上限（下限 1：传 0 或负数没有业务含义，宁可让调用方显式传"长期"=null）。 */
    private static final int MAX_EXPIRE_DAYS = 365;

    /** 码长度：32 字符列的一半，16 位 · 31 字符集 ≈ 79 bit 熵，暴力枚举无意义。 */
    private static final int CODE_LENGTH = 16;

    /** 字符集：剔除易混淆的 I / O / 0 / 1。 */
    private static final char[] ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private static final SecureRandom RANDOM = new SecureRandom();

    private final InviteCodeMapper inviteCodeMapper;
    private final AdminOperationLogger operationLogger;

    public AdminInviteService(InviteCodeMapper inviteCodeMapper, AdminOperationLogger operationLogger) {
        this.inviteCodeMapper = inviteCodeMapper;
        this.operationLogger = operationLogger;
    }

    /**
     * 生成邀请码（§6.11 {@code POST /api/admin/invite-codes}）。
     *
     * @param adminId    操作管理员 id
     * @param count      生成数量（1–50，默认 1）
     * @param expireDays 有效天数（1–365；null = 永不过期）
     * @param ip         操作来源 IP
     * @return 生成的邀请码字符串列表（顺序即落库顺序）
     */
    @Transactional
    public List<String> generate(long adminId, Integer count, Integer expireDays, String ip) {
        int n = count == null ? 1 : count;
        if (n < 1 || n > MAX_BATCH) {
            throw new BizException(ErrorCode.BAD_REQUEST, "count 必须在 1–" + MAX_BATCH + " 之间，收到：" + n);
        }
        if (expireDays != null && (expireDays < 1 || expireDays > MAX_EXPIRE_DAYS)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "expireDays 必须在 1–" + MAX_EXPIRE_DAYS + " 之间（null 表示永不过期），收到：" + expireDays);
        }
        LocalDateTime expireAt = expireDays == null ? null : LocalDateTime.now().plusDays(expireDays);

        // 一批码、一次留痕：留痕对象是"生成动作"（targetId=null，detail 记数量）
        List<String> codes = new java.util.ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            codes.add(insertWithRetry(expireAt));
        }
        operationLogger.log(adminId, ACTION_INVITE_CODE,
                AdminOperationLog.TARGET_INVITE_CODE, null, null,
                "count:" + n, ip);
        log.info("邀请码生成：adminId={} count={} expireDays={}", adminId, n, expireDays);
        return codes;
    }

    /** 后台邀请码列表（可按 status 筛选；id 倒序 = 新生成的在前）。 */
    @Transactional(readOnly = true)
    public PageResult<AdminInviteCodeVO> list(Integer status, int page, int size) {
        if (status != null && !Set.of(InviteCode.STATUS_UNUSED, InviteCode.STATUS_USED,
                InviteCode.STATUS_DISABLED).contains(status)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "status 只支持 0（未使用）/ 1（已使用）/ 2（已失效），收到：" + status);
        }
        int pageNo = PageResult.normalizePage(page);
        int pageSize = PageResult.normalizeSize(size);

        IPage<InviteCode> result = inviteCodeMapper.selectPage(new Page<>(pageNo, pageSize),
                Wrappers.<InviteCode>lambdaQuery()
                        .eq(status != null, InviteCode::getStatus, status)
                        .orderByDesc(InviteCode::getId));

        List<AdminInviteCodeVO> items = result.getRecords().stream()
                .map(row -> new AdminInviteCodeVO(
                        row.getId(),
                        row.getCode(),
                        row.getStatus() == null ? 0 : row.getStatus(),
                        row.getExpireAt(),
                        row.getUsedByUserId(),
                        row.getUsedAt(),
                        row.getCreatedAt()))
                .toList();
        return PageResult.of(items, result.getTotal(), pageNo, pageSize);
    }

    /** 插入一条新码；撞 {@code uk_code}（16 位随机理论概率≈0，防御性兜底）重试最多 5 次。 */
    private String insertWithRetry(LocalDateTime expireAt) {
        for (int attempt = 0; attempt < 5; attempt++) {
            InviteCode inviteCode = new InviteCode();
            String code = randomCode();
            inviteCode.setCode(code);
            inviteCode.setCreatorUserId(null); // 管理员不是前台用户，creator_user_id 留空
            inviteCode.setStatus(InviteCode.STATUS_UNUSED);
            inviteCode.setExpireAt(expireAt);
            try {
                inviteCodeMapper.insert(inviteCode);
                return code;
            } catch (DuplicateKeyException ex) {
                log.warn("邀请码撞 uk_code，重试第 {} 次", attempt + 1);
            }
        }
        throw new BizException(ErrorCode.INTERNAL_ERROR, "邀请码生成连续撞码（异常，请检查随机源）");
    }

    private static String randomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
