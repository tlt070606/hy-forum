package com.hyforum.auth.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hyforum.common.oss.AvatarUrlResolver;
import com.hyforum.auth.captcha.CaptchaService;
import com.hyforum.auth.dto.LoginRequest;
import com.hyforum.auth.dto.RegisterRequest;
import com.hyforum.auth.mode.RegisterMode;
import com.hyforum.auth.mode.RegisterModeService;
import com.hyforum.auth.vo.LoginVO;
import com.hyforum.common.api.ErrorCode;
import com.hyforum.common.audit.SensitiveTextChecker;
import com.hyforum.common.exception.BizException;
import com.hyforum.common.security.AccountStatusChecker;
import com.hyforum.common.security.CurrentUser;
import com.hyforum.common.security.StpUserUtil;
import com.hyforum.domain.admin.entity.Admin;
import com.hyforum.domain.admin.mapper.AdminMapper;
import com.hyforum.domain.invite.entity.InviteCode;
import com.hyforum.domain.invite.mapper.InviteCodeMapper;
import com.hyforum.domain.user.entity.User;
import com.hyforum.domain.user.mapper.UserMapper;
import com.hyforum.domain.user.vo.UserVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 认证服务：注册 / 登录 / 登出 / 当前用户（docs/技术方案.md §6.2 / §6.3）。
 *
 * <p>本类是 M1 的核心，实现口径逐条对应契约：</p>
 * <ul>
 *   <li>{@link #register}：验证码 → 参数 → 用户名唯一 → 注册模式（open/invite/closed）→ 落库；</li>
 *   <li>{@link #login}：账号状态 → 密码校验 → 下发 token；</li>
 *   <li>{@link #logout}：注销当前 token；</li>
 *   <li>{@link #currentUser}：{@code GET /api/user/me}。</li>
 * </ul>
 *
 * <p><b>校验顺序是刻意的</b>（先"请求自身是否合法"，再"业务规则是否允许"，最后"是否与现状冲突"）：</p>
 * <ol>
 *   <li>验证码 1003 —— 最便宜、且是所有注册的必经门槛；</li>
 *   <li>字段格式 400（用户名/密码/昵称/协议同意）—— 不查库；</li>
 *   <li>敏感词 2001 —— 内存匹配，不查库（§8.6 要求内容入口必须过滤）；</li>
 *   <li>用户名唯一 1001 —— 一次点查；</li>
 *   <li>注册模式 403 —— 一次点查 + 邀请码条件更新。</li>
 * </ol>
 * <p>这样做的意义：注册模式处于 {@code closed} 时，一个格式非法的请求得到的是 400 而不是 403，
 * 测试与前端都能稳定复现，不会因为"当前是什么模式"而给出不同错误码（避免用例间互相干扰）。</p>
 *
 * <p>同时实现 {@link AccountStatusChecker}：登录态只保证 token 有效，不保证账号仍可用，
 * 拦截器每次请求都要查一次封禁状态（见该接口的说明）。</p>
 */
@Service
public class AuthService implements AccountStatusChecker {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /** 用户名格式：4–20 位字母数字下划线（技术方案 §6.2）。 */
    private static final String USERNAME_PATTERN = "^[A-Za-z0-9_]{4,20}$";

    /** 昵称长度上限（技术方案 §6.2）。 */
    private static final int NICKNAME_MAX_LENGTH = 20;

    /** 账号状态：1 正常。 */
    private static final int STATUS_NORMAL = 1;

    private final UserMapper userMapper;
    private final AdminMapper adminMapper;
    private final InviteCodeMapper inviteCodeMapper;
    private final PasswordEncoder passwordEncoder;
    private final CaptchaService captchaService;
    private final RegisterModeService registerModeService;
    private final SensitiveTextChecker sensitiveTextChecker;

    /** 头像 URL 的唯一装配入口（CR-Q）。 */
    private final AvatarUrlResolver avatarResolver;

    public AuthService(UserMapper userMapper,
                       AdminMapper adminMapper,
                       InviteCodeMapper inviteCodeMapper,
                       PasswordEncoder passwordEncoder,
                       CaptchaService captchaService,
                       RegisterModeService registerModeService,
                          SensitiveTextChecker sensitiveTextChecker,
                          AvatarUrlResolver avatarResolver) {
        this.userMapper = userMapper;
        this.adminMapper = adminMapper;
        this.inviteCodeMapper = inviteCodeMapper;
        this.passwordEncoder = passwordEncoder;
        this.captchaService = captchaService;
        this.registerModeService = registerModeService;
        this.sensitiveTextChecker = sensitiveTextChecker;
        this.avatarResolver = avatarResolver;
    }

    // ==================================================================
    // 注册
    // ==================================================================

    /**
     * 注册（docs/技术方案.md §6.2 + §8.8）。
     *
     * <p>整体在一个事务里：邀请制下"用户落库"与"邀请码置为已使用"必须同生共死 ——
     * 条件更新影响行数为 0 时抛异常回滚，绝不留下"用了码但没建号"或"建了号但码没消耗"的中间态。</p>
     */
    @Transactional
    public UserVO register(RegisterRequest request) {
        // ---------- ① 验证码：一次性消费，失败即 1003 ----------
        if (!captchaService.verifyAndConsume(request.captchaUuid(), request.captchaCode())) {
            throw new BizException(ErrorCode.CAPTCHA_INVALID);
        }

        // ---------- ② 字段格式校验（注解已挡一层，这里挡 Service 直调的场景） ----------
        validateFields(request);

        // ---------- ③ 内容安全：昵称是用户自由文本，属于内容入口 ----------
        if (sensitiveTextChecker.containsSensitive(request.nickname())
                || sensitiveTextChecker.containsSensitive(request.username())) {
            throw new BizException(ErrorCode.SENSITIVE_CONTENT);
        }

        // ---------- ④ 用户名唯一（1001） ----------
        // 先查一次是为了给出准确错误码；并发下的兜底是下面的 DuplicateKeyException 捕获。
        // 两次防线都必要：只靠查询会有竞态，只靠唯一索引则拿不到"是用户名重复"这一语义。
        if (existsByUsername(request.username())) {
            throw new BizException(ErrorCode.USERNAME_EXISTS);
        }

        // ---------- ⑤ 注册模式（§8.8） ----------
        RegisterMode mode = registerModeService.currentMode();
        if (mode == RegisterMode.CLOSED) {
            // 完全关闭注册：仅保留已注册用户登录（管理员另有独立入口，不受影响）
            throw new BizException(ErrorCode.FORBIDDEN, "站点已关闭注册");
        }
        if (mode == RegisterMode.INVITE && (request.inviteCode() == null || request.inviteCode().isBlank())) {
            throw new BizException(ErrorCode.FORBIDDEN, "当前为邀请制注册，必须提供邀请码");
        }

        // ---------- ⑥ 落库 ----------
        User user = new User();
        user.setUsername(request.username());
        // BCrypt strength=10（技术方案 §9）：绝不存明文，也不用可逆加密
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setNickname(request.nickname());
        user.setGender(0);
        user.setStatus(STATUS_NORMAL);
        user.setLevel(1);
        user.setIsDeleted(0);
        try {
            userMapper.insert(user);
        } catch (DuplicateKeyException ex) {
            // 唯一索引 uk_username 兜住了"查完到插入之间"的并发注册
            throw new BizException(ErrorCode.USERNAME_EXISTS, ErrorCode.USERNAME_EXISTS.message(), ex);
        }

        // ---------- ⑦ 邀请码：条件更新 + 影响行数校验（P1-4 定案） ----------
        if (mode == RegisterMode.INVITE) {
            consumeInviteCode(request.inviteCode(), user.getId());
        }

        log.info("用户注册成功 id={} username={} mode={}", user.getId(), user.getUsername(), mode.value());
        return UserVO.from(user, avatarResolver);
    }

    /**
     * 消费邀请码：<b>只允许"条件更新 + 影响行数校验"这一种写法</b>（P1-4 定案，技术方案 §8.8）。
     *
     * <p>绝不允许改成"先 SELECT 判断 status=0，再 UPDATE"：在 RC/RR 下两个并发事务都会
     * 读到 {@code status=0} 并各自更新成功，同一个码被用两次。InnoDB 的 UPDATE 是当前读，
     * 第二个事务会看到已提交的 {@code status=1}，WHERE 不匹配 → 影响行数 0。</p>
     *
     * <p>顺序遵循契约给定的写法（先 INSERT user 拿 id → 再条件更新），因此失败时回滚会
     * 消耗掉一个自增 id。这是契约写明的顺序，不做"优化" —— 自增 id 空洞无害，
     * 而改动顺序会让实现与 §8.8 的示例不一致，后续评审无法逐行对照。</p>
     *
     * @param rawCode 用户提交的邀请码原文
     * @param userId  刚创建的用户 id
     */
    private void consumeInviteCode(String rawCode, Long userId) {
        // 先做一次存在性检查，只为了给出"邀请码不存在"的更准确提示；
        // 真正的一致性防线是下面那条 UPDATE 的影响行数，不是这次查询。
        String code = rawCode.trim();
        boolean exists = inviteCodeMapper.selectCount(
                Wrappers.<InviteCode>lambdaQuery().eq(InviteCode::getCode, code)) > 0;
        if (!exists) {
            throw new BizException(ErrorCode.FORBIDDEN, "邀请码不存在");
        }

        int affected = inviteCodeMapper.markUsedIfAvailable(code, userId);
        if (affected != 1) {
            // 影响行数不为 1：码已被使用 / 已失效 / 已过期，三种情况一并拒绝并回滚整个注册事务
            throw new BizException(ErrorCode.FORBIDDEN, "邀请码无效、已失效或已被使用");
        }
    }

    // ==================================================================
    // 登录 / 登出
    // ==================================================================

    /**
     * 登录（docs/技术方案.md §6.2）。
     *
     * <p>基于 Sa-Token 的<b>前台</b> StpLogic 下发 token（与后台 admin 完全隔离，§9）。</p>
     */
    @Transactional
    public LoginVO login(LoginRequest request) {
        User user = findByUsername(request.username());
        // 统一用 1002 应对"用户不存在"与"密码错误"：区分开会让攻击者能枚举出有效用户名。
        // 但"被封禁"必须与"密码错误"区分（契约给了 1004），否则用户永远不知道自己被封了。
        if (user == null) {
            throw new BizException(ErrorCode.BAD_CREDENTIALS);
        }
        if (user.getStatus() == null || user.getStatus() != STATUS_NORMAL) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }
        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new BizException(ErrorCode.BAD_CREDENTIALS);
        }

        String token = StpUserUtil.login(user.getId());

        // 记录最后登录时间：失败不影响登录成功本身，因此不让异常冒泡
        try {
            User update = new User();
            update.setId(user.getId());
            update.setLastLoginAt(LocalDateTime.now());
            userMapper.updateById(update);
        } catch (RuntimeException ex) {
            log.warn("更新 last_login_at 失败 userId={}：{}", user.getId(), ex.getMessage());
        }

        return new LoginVO(token, UserVO.from(user, avatarResolver));
    }

    /** 注销当前 token（技术方案 §6.2：{@code POST /api/auth/logout}，需要登录）。 */
    public void logout() {
        StpUserUtil.logout();
    }

    /**
     * 当前登录用户信息（{@code GET /api/user/me}，§6.3）。
     *
     * <p>用户 id 从 {@link CurrentUser} 取（由拦截器校验后写入），<b>不接受前端传 id</b> ——
     * 一旦允许传参，就又多了一个越权面。</p>
     */
    @Transactional(readOnly = true)
    public UserVO currentUser() {
        Long userId = CurrentUser.requireId();
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED);
        }
        return UserVO.from(user, avatarResolver);
    }

    // ==================================================================
    // AccountStatusChecker：拦截器每次请求都调
    // ==================================================================

    @Override
    @Transactional(readOnly = true)
    public boolean isUserActive(Long userId) {
        if (userId == null) {
            return false;
        }
        User user = userMapper.selectById(userId);
        return user != null && user.getStatus() != null && user.getStatus() == STATUS_NORMAL;
    }

    /**
     * 管理员可用性检查。
     *
     * <p>本方法由 {@code com.hyforum.admin} 模块的登录服务承担更合适，
     * 但 {@link AccountStatusChecker} 的 user 侧与 admin 侧若由两个 Bean 分别实现同一接口，
     * 注入时就要靠 {@code @Primary}/{@code @Qualifier} 区分，反而更脆。</p>
     *
     * <p>折中做法：把 admin 查询委托给 {@code com.hyforum.domain} 的 AdminMapper ——
     * 这是<b>数据访问</b>而非跨业务模块调用，符合铁律 3（业务模块只允许依赖 common 与 domain）。
     * admin 模块自己的登录逻辑仍然独立实现。</p>
     */
    @Override
    @Transactional(readOnly = true)
    public boolean isAdminActive(Long adminId) {
        if (adminId == null) {
            return false;
        }
        Admin admin = adminMapper.selectById(adminId);
        return admin != null && admin.getStatus() != null && admin.getStatus() == STATUS_NORMAL;
    }

    // ==================================================================
    // 内部工具
    // ==================================================================

    private void validateFields(RegisterRequest request) {
        if (request.username() == null || !request.username().matches(USERNAME_PATTERN)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "用户名需为 4-20 位字母、数字或下划线");
        }
        if (request.password() == null || !isPasswordStrong(request.password())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "密码需为 8-32 位，且至少包含字母与数字");
        }
        if (request.nickname() == null || request.nickname().isBlank()
                || request.nickname().codePointCount(0, request.nickname().length()) > NICKNAME_MAX_LENGTH) {
            throw new BizException(ErrorCode.BAD_REQUEST, "昵称长度需为 1-20 字符");
        }
        // 合规 C1：用户协议必须主动同意。用"必须为 true"而不是"不能为 false"，
        // 这样字段缺失（null）同样被拒绝 —— 缺省放行是合规红线上的错误方向。
        if (!Boolean.TRUE.equals(request.agreeProtocol())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "必须同意用户协议与隐私政策后才能注册");
        }
    }

    /**
     * 密码强度判定，规则与 {@code com.hyforum.auth.validation.PasswordValidator} 一致。
     *
     * <p>为什么两处都做：注解在 Controller 层拦住绝大多数请求并给出字段级提示；
     * Service 层再判一次是为了 Service 被直接调用（例如测试、未来的内部批量导入）时
     * 规则不会绕过。<b>两处判定的口径必须一致</b>，因此这里复用同一组常量。</p>
     */
    private boolean isPasswordStrong(String password) {
        int length = password.codePointCount(0, password.length());
        if (length < com.hyforum.auth.validation.PasswordValidator.MIN_LENGTH
                || length > com.hyforum.auth.validation.PasswordValidator.MAX_LENGTH) {
            return false;
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < password.length(); i++) {
            char c = password.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
            } else if (Character.isDigit(c)) {
                hasDigit = true;
            }
        }
        return hasLetter && hasDigit;
    }

    private boolean existsByUsername(String username) {
        return userMapper.selectCount(
                Wrappers.<User>lambdaQuery().eq(User::getUsername, username)) > 0;
    }

    private User findByUsername(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return userMapper.selectOne(Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
    }
}
