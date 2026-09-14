package com.hyforum.admin.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hyforum.admin.vo.AdminLoginVO;
import com.hyforum.common.api.ErrorCode;
import com.hyforum.common.exception.BizException;
import com.hyforum.common.security.StpAdminUtil;
import com.hyforum.domain.admin.entity.Admin;
import com.hyforum.domain.admin.mapper.AdminMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 后台管理员登录（docs/技术方案.md §6.11 第一行）。
 *
 * <p><b>为什么 M1 就要实现后台登录</b>：验收项 {@code M1_admin_unaffected_by_register_mode}
 * 要求"注册模式 {@code closed} 下管理员仍可登录"（§8.8 最后一条设计要点）。没有后台登录端点
 * 就无法验证这条不变量，而它正是"把注册关掉之后还能不能进后台运维"的关键 —— 属于必须成立的
 * 安全/可用性前提，不能留到 M6 再补。</p>
 *
 * <p><b>与开放注册完全无关</b>：本类<b>不读取</b> {@code register_mode}。
 * 这就是"管理员不受注册模式影响"的实现方式 —— 不是加一个 if 判断，
 * 而是让这条代码路径根本不经过注册模式。管理员账号由数据库/后台新增（§8.8：
 * "管理员账号不受注册模式影响，可随时新增"）。</p>
 *
 * <p>M1 只实现 {@code /api/admin/login} 与 {@code /logout}。§6.11 的其余后台接口
 * （帖子审核、评论审核、用户封禁、配置与邀请码管理……）依赖 M3/M4/M5 的业务数据模型，
 * 由 M6 实现，本里程碑不提前占位。</p>
 */
@Service
public class AdminAuthService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);

    /** 状态：1 正常（admin 表没有 is_deleted 列，停用走 status）。 */
    private static final int STATUS_NORMAL = 1;

    private final AdminMapper adminMapper;
    private final PasswordEncoder passwordEncoder;

    public AdminAuthService(AdminMapper adminMapper, PasswordEncoder passwordEncoder) {
        this.adminMapper = adminMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 管理员登录。
     *
     * <p>错误码复用 §6.1 的约定：用户名或密码错误 → 1002；账号被停用 → 1004。
     * 契约没有为"管理员"另设错误码，因此复用前台的码，前端不需要为后台准备第二套解析。</p>
     *
     * @param username 管理员登录名
     * @param password 明文密码
     * @return token 与管理员基本信息
     */
    @Transactional
    public AdminLoginVO login(String username, String password) {
        Admin admin = findActiveByUsername(username);
        // 统一 1002：不区分"管理员不存在"与"密码错误"，避免账号枚举
        if (admin == null) {
            throw new BizException(ErrorCode.BAD_CREDENTIALS);
        }
        if (admin.getStatus() == null || admin.getStatus() != STATUS_NORMAL) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }
        if (!passwordEncoder.matches(password, admin.getPasswordHash())) {
            // seed.sql 里的 password_hash 是占位符，登录必然失败（fail-closed），
            // 这里留下明确线索便于运维知道"要先给管理员设置真实密码"
            log.warn("管理员登录失败（密码不匹配）username={}", username);
            throw new BizException(ErrorCode.BAD_CREDENTIALS);
        }

        String token = StpAdminUtil.login(admin.getId());

        try {
            Admin update = new Admin();
            update.setId(admin.getId());
            update.setLastLoginAt(LocalDateTime.now());
            adminMapper.updateById(update);
        } catch (RuntimeException ex) {
            log.warn("更新管理员 last_login_at 失败 adminId={}：{}", admin.getId(), ex.getMessage());
        }

        return new AdminLoginVO(token, admin.getId(), admin.getNickname(), admin.getRole());
    }

    /** 注销后台登录态。 */
    public void logout() {
        StpAdminUtil.logout();
    }

    /** 按用户名查管理员（只查一次，状态判断交给调用方，避免重复查询）。 */
    private Admin findActiveByUsername(String username) {
        if (username == null || username.isBlank()) {
            return null;
        }
        return adminMapper.selectOne(Wrappers.<Admin>lambdaQuery().eq(Admin::getUsername, username));
    }
}
