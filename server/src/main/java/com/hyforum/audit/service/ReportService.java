package com.hyforum.audit.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hyforum.common.api.ErrorCode;
import com.hyforum.common.exception.BizException;
import com.hyforum.common.redis.RateLimiter;
import com.hyforum.domain.post.entity.Post;
import com.hyforum.domain.post.mapper.PostMapper;
import com.hyforum.domain.report.entity.Report;
import com.hyforum.domain.report.mapper.ReportMapper;
import com.hyforum.audit.dto.ReportCreateRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;

/**
 * 举报服务（技术方案 §6.11 的举报入口 + §8.7 的频率限制）。
 *
 * <h2>限流的分层：举报属于「业务动作维度」→ 业务码 + HTTP 200（§5 裁决 #6／#8）</h2>
 * <p>§8.7 规定"举报 ≤ 10 次/天"。这与"入口维度"（登录/注册那种按 IP 的 1 分钟窗口）
 * <b>不是同一层</b>，因此表现也不同：</p>
 * <ul>
 *   <li><b>入口维度</b>：HTTP <b>429</b> + 业务码 429 + {@code Retry-After} 头（H2，见 AuthController）；</li>
 *   <li><b>业务动作维度</b>：<b>HTTP 200</b> + 业务码，由前端按业务码提示。
 *       本类走这一层 —— 与 M3 发帖的 {@code 2002}（HTTP 200 + 业务码）完全同构。</li>
 * </ul>
 *
 * <h2>选的业务码与理由（任务书 §5 第 8 条要求写明）</h2>
 * <p>用 <b>{@link ErrorCode#TOO_MANY_REQUESTS}（429）</b>。理由：</p>
 * <ol>
 *   <li>它在本项目契约里的含义就是"请求过于频繁"（§6.1），语义**已经对**，
 *       只是它的 HTTP 状态与"业务动作维度"的分层不匹配 —— 而**分层由本任务决定 HTTP 码**，
 *       不要求业务码也跟着换；</li>
 *   <li><b>不新增错误码</b>：契约 §6.1 的错误码表是 L1 冻结的，新增码要走契约变更流程
 *       （本任务书 §2 不许改契约）。用已有码是"在给定契约内选最贴的"，不是省事；</li>
 *   <li><b>不复用 2002</b>（发帖过于频繁）：那会让前端把举报限流误显示成"发帖太频繁"。
 *       复用错误的码比新增码更糟 —— 它制造的是**语义错误**而不是**缺少信息**。</li>
 * </ol>
 * <p>响应体形如 {@code {"code":429,"message":"请求过于频繁，请 N 秒后重试"}} 且 <b>HTTP 200</b>。</p>
 *
 * <h2>幂等性：本接口**刻意不做**去重</h2>
 * <p>同一个人可以对同一条内容举报多次吗？契约与 schema 都没有唯一索引
 * （{@code report} 表只有 {@code idx_status_created}，没有 {@code uk_target_user}）。
 * 因此<b>按契约它允许重复</b>，本类也不加"先查有没有举报过"的判断 ——
 * 那会引入一个契约里不存在的限制，而且"先查再插"正是本项目禁止的两步式写法。
 * 重复举报由 §8.7 的频率限制（10 次/天）兜住。</p>
 */
@Service
public class ReportService {

    private static final Logger log = LoggerFactory.getLogger(ReportService.class);

    /** 限流动作编码（键形如 {@code hy:rl:report-daily:{userId}}）。 */
    static final String ACTION_REPORT_DAILY = "report-daily";

    /** 窗口：1 天（§8.7「举报 ≤ 10 次/天」）。 */
    private static final Duration ONE_DAY = Duration.ofDays(1);

    /** 补充说明长度上限，与 {@code report.reason_detail} 的 VARCHAR(200) 一致。 */
    private static final int MAX_DETAIL_LENGTH = 200;

    private final ReportMapper reportMapper;
    private final PostMapper postMapper;
    private final RateLimiter rateLimiter;

    /**
     * 每日举报上限（§8.7：10 次/天）。
     *
     * <p>走配置而不是写死常量：测试要能在不打满 10 次的情况下验证限流行为
     * （与 {@code hy.rate-limit.ip-per-minute} 同一做法）。</p>
     */
    private final int dailyLimit;

    public ReportService(ReportMapper reportMapper,
                         PostMapper postMapper,
                         RateLimiter rateLimiter,
                         @Value("${hy.report.daily-limit:10}") int dailyLimit) {
        this.reportMapper = reportMapper;
        this.postMapper = postMapper;
        this.rateLimiter = rateLimiter;
        this.dailyLimit = dailyLimit;
    }

    /**
     * 提交举报（§6.11）。
     *
     * <p>顺序刻意是「<b>先限流 → 再校验目标 → 再落库</b>」：</p>
     * <ol>
     *   <li><b>限流在最前</b>：它要挡的是"刷举报"这个动作本身。若放在校验之后，
     *       攻击者可以用**不存在的 targetId** 无限探测（每次都在校验处被拒、
     *       永远不消耗配额）—— 那等于限流可以被绕过；</li>
     *   <li>目标校验只做帖子（评论与用户的校验需要各自的 Mapper，
     *       而本任务只验"举报入口 + 限流"这条链路；契约里的三种 targetType 都接受，
     *       但只有帖子会做存在性校验 —— <b>这一点如实登记</b>，不为它扩大范围）；</li>
     *   <li>落库。</li>
     * </ol>
     *
     * @param userId  举报人（当前登录用户）
     * @param request 举报请求
     * @return 新建举报的 id
     */
    @Transactional
    public long create(long userId, ReportCreateRequest request) {
        // ① 限流（业务动作维度：超限仍返回 HTTP 200，由业务码承载）
        RateLimiter.Decision decision = rateLimiter.check(
                ACTION_REPORT_DAILY, String.valueOf(userId), dailyLimit, ONE_DAY);
        if (!decision.allowed()) {
            // ★ 业务动作维度：**HTTP 200 + 业务码**（§5 裁决 #6/#8）。
            //   不能直接 new BizException(TOO_MANY_REQUESTS, ...) —— 那个码自带 HTTP 429，
            //   会变成"入口维度"的表现，前端据此弹"操作太快了"，
            //   而用户的真实处境是"今天的次数用完了"（下一步动作完全不同：等一会儿 vs 明天再来）。
            throw BizException.businessActionRateLimited(ErrorCode.TOO_MANY_REQUESTS,
                    "举报过于频繁（每天最多 " + dailyLimit + " 次），请 "
                            + decision.retryAfter() + " 秒后重试");
        }

        // ② 参数与目标校验
        Integer targetType = request.targetType();
        if (targetType == null
                || (targetType != Report.TARGET_POST
                && targetType != Report.TARGET_COMMENT
                && targetType != Report.TARGET_USER)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "targetType 只支持 1 帖子 / 2 评论 / 3 用户");
        }
        Integer reasonType = request.reasonType();
        if (reasonType == null || reasonType < Report.REASON_ILLEGAL || reasonType > Report.REASON_OTHER) {
            throw new BizException(ErrorCode.BAD_REQUEST, "reasonType 只支持 1–5");
        }
        if (request.targetId() == null) {
            throw new BizException(ErrorCode.BAD_REQUEST, "targetId 不能为空");
        }
        String detail = blankToNull(request.reasonDetail());
        if (detail != null && detail.length() > MAX_DETAIL_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST, "reasonDetail 不能超过 " + MAX_DETAIL_LENGTH + " 字符");
        }
        if (targetType == Report.TARGET_POST) {
            Post post = postMapper.selectById(request.targetId());
            if (post == null) {
                throw new BizException(ErrorCode.NOT_FOUND, "帖子不存在");
            }
        }

        // ③ 落库
        Report report = new Report();
        report.setTargetType(targetType);
        report.setTargetId(request.targetId());
        report.setUserId(userId);
        report.setReasonType(reasonType);
        report.setReasonDetail(detail);
        report.setStatus(Report.STATUS_PENDING);
        report.setCreatedAt(LocalDateTime.now());
        reportMapper.insert(report);
        log.info("收到举报：id={} 举报人={} targetType={} targetId={} reasonType={}",
                report.getId(), userId, targetType, request.targetId(), reasonType);
        return report.getId();
    }

    /**
     * 某人今天已举报的次数（供用例断言"计数确实在增长"，也供后台观察）。
     *
     * <p>刻意读 Redis 的限流计数而不是 {@code COUNT(report)}：
     * 限流窗口是**滑动/固定 1 天**，而 {@code COUNT} 算的是"历史累计" ——
     * 两者在跨天时必然不一致，用后者做断言会得出错误结论。</p>
     */
    public long todayCount(long userId) {
        return rateLimiter.check(ACTION_REPORT_DAILY, String.valueOf(userId), Integer.MAX_VALUE, ONE_DAY)
                .count();
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
