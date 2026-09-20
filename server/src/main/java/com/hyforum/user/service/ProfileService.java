package com.hyforum.user.service;

import com.hyforum.common.api.ErrorCode;
import com.hyforum.common.exception.BizException;
import com.hyforum.common.oss.OssProperties;
import com.hyforum.domain.user.entity.User;
import com.hyforum.domain.user.mapper.UserMapper;
import com.hyforum.user.dto.ProfileUpdateRequest;
import com.hyforum.user.vo.UserProfileVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 修改自己资料（任务书 §14：{@code PUT /api/user/profile}）。
 *
 * <h2>PUT = 覆盖（省略即清空）</h2>
 * <p>{@code nickname} 必填；{@code avatarUrl}/{@code bio} 不传即置 null（清空）；
 * {@code gender} 不传按 {@code 0}（未设置）。这是 PUT 的标准语义，
 * 也让"清空头像/简介"不需要额外交一个 DELETE 端点。</p>
 *
 * <h2>只能改自己</h2>
 * <p>用户 id <b>只</b>来自登录态（Controller 传 {@code CurrentUser.requireId()}），
 * 请求体里没有、也不接受 {@code userId} —— 有的话它会被
 * {@code ProfileUpdateRequest} 的"未知字段即 400"挡住（见那个类的注释）。
 * 这比"接受 userId 再校验是否等于自己"更省事也更安全：
 * <b>没有那个参数，就不存在"忘了校验"这种失误</b>。</p>
 *
 * <h2>头像归属校验：必须在自己那个目录下（§14.3 第 4 条，不许放宽）</h2>
 * <p>前缀取自 {@link OssProperties#userAvatarUrlPrefix(long)}，与
 * "签名下发的 {@code dir}" 同一个来源（§14.2 ②）。
 * 放宽的后果很具体：用户可以指向<b>任意 URL</b> → 前台去渲染
 * <b>别人服务器上的图</b> —— 盗链、隐私泄露、图片审核面失控三件事同时发生，
 * 而且后端日志里一个错都没有。因此校验逻辑与帖子图完全对称：
 * {@code url.startsWith(前缀)}，配置缺失时前缀为空 → <b>拒绝一切</b>（fail-closed）。</p>
 */
@Service
public class ProfileService {

    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);

    private final UserMapper userMapper;
    private final OssProperties ossProperties;

    public ProfileService(UserMapper userMapper, OssProperties ossProperties) {
        this.userMapper = userMapper;
        this.ossProperties = ossProperties;
    }

    /**
     * 覆盖式更新自己的资料。
     *
     * @param userId  当前登录用户 id（<b>只来自登录态</b>）
     * @param request 请求体（只有 4 个可编辑字段，其余字段已在反序列化时被拒）
     * @return 更新后的个人主页视图
     */
    @Transactional
    public UserProfileVO updateProfile(long userId, ProfileUpdateRequest request) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            // 登录态有效但用户不在了（注销/物理删除）：按 404 处理而不是 500
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }

        String nickname = request.nickname() == null ? null : request.nickname().trim();
        if (nickname == null || nickname.isEmpty()) {
            // @NotBlank 已经挡了一层，这里再挡一次：Service 也可能被别的调用方直接调（防御绕过 DTO 的路径）
            throw new BizException(ErrorCode.BAD_REQUEST, "昵称不能为空");
        }
        // 昵称上限与列宽（VARCHAR(20)）一致。@Size 已挡，这里同样做第二道 ——
        // 超长会是 SQL 报错（500），而它本该是 400
        if (nickname.length() > 20) {
            throw new BizException(ErrorCode.BAD_REQUEST, "昵称不能超过 20 字");
        }

        String avatarUrl = blankToNull(request.avatarUrl());
        if (avatarUrl != null) {
            requireOwnAvatarDirectory(userId, avatarUrl);
        }
        String bio = blankToNull(request.bio());
        if (bio != null && bio.length() > 200) {
            throw new BizException(ErrorCode.BAD_REQUEST, "简介不能超过 200 字");
        }
        int gender = normalizeGender(request.gender());
        log.info("用户 {} 更新资料：avatarUrl 是否设置={} bio 是否设置={} gender={}",
                userId, avatarUrl != null, bio != null, gender);

        // ★ 返回"刚刚应用的三个值"，而不是重新读一遍实体、也**不是**用手上这个陈旧实体。
        //
        //   这里踩过一次（被用例当场抓住，如实记录）：
        //   第一版把**更新前**读出来的 `user` 传给了 toProfileVO ——
        //   于是响应里是旧昵称/旧头像，而库里其实已经改成功了。
        //   现象最恶劣的一点是：**它看起来像"改资料没生效"**，
        //   客户端据此显示旧值，用户以为保存失败、反复重试 ——
        //   也就是本项目反复吃过的"界面在说谎"，只不过这次说谎的是**接口响应**。
        //
        //   为什么选"用刚应用的值"而不是"再 selectById 读一次"：
        //   ① 刚写进去的就是真值，不需要再查（少一次查询）；
        //   ② 再读一次也**不能**证明写入成功 —— 它只是把同样的信任问题推迟了一步。
        //      真要证明，靠的是下面这条：affected rows 必须为 1。
        int affected = userMapper.update(null, com.baomidou.mybatisplus.core.toolkit.Wrappers
                .<User>lambdaUpdate()
                .eq(User::getId, userId)
                .set(User::getNickname, nickname)
                // ★ PUT 覆盖语义：不传就是 null（清空），不是"保持原值"。
                //   用显式 set(null) 而不是"值非空才 set" —— 后者会让"清空"变成一个做不到的操作。
                .set(User::getAvatarUrl, avatarUrl)
                .set(User::getBio, bio)
                .set(User::getGender, gender));
        if (affected != 1) {
            // 0 行说明"这个 id 在更新语句执行时已经不存在了"（并发注销/删除）。
            // 不静默返回成功：那会让客户端拿到一份"看起来改好了"的响应，而库里什么都没变。
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在或已被删除，资料未更新");
        }
        return toProfileVO(user, nickname, avatarUrl, bio, gender);
    }

    /**
     * 头像必须是**该用户自己目录下**的对象。
     *
     * <p>与帖子图的归属校验同一形状（{@code startsWith(前缀)}），但前缀多了一层
     * {@code {userId}} —— 这正是 §14.2 ① 要的效果：A 不能把头像设成 B 的对象。</p>
     */
    private void requireOwnAvatarDirectory(long userId, String avatarUrl) {
        String allowedPrefix = ossProperties.userAvatarUrlPrefix(userId);
        if (allowedPrefix.isEmpty()) {
            // fail-closed：与帖子图同一口径（见 OssProperties 类注释）。
            // 放行会让"防外部图片"这条校验在配置缺失时静默失效 ——
            // 前台开始渲染别人的服务器，而日志里没有错。
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "本站尚未配置 OSS 前缀，暂不接受头像地址；请先配置环境变量 OSS_ENDPOINT / OSS_BUCKET");
        }
        if (!avatarUrl.startsWith(allowedPrefix)) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "头像地址必须是本人头像目录下的对象（" + allowedPrefix + "），"
                            + "不接受外部地址");
        }
    }

    /** 性别只接受 0/1/2（与 {@code user.gender} 的列注释一致）；不传按 0。 */
    private static int normalizeGender(Integer gender) {
        if (gender == null) {
            return 0;
        }
        if (gender < 0 || gender > 2) {
            throw new BizException(ErrorCode.BAD_REQUEST, "gender 只支持 0 未知 / 1 男 / 2 女");
        }
        return gender;
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /**
     * 组装更新后的主页视图。
     *
     * <p><b>四个可编辑字段用"刚应用的值"传进来</b>（见 {@link #updateProfile} 的注释：
     * 用手上的陈旧实体做过一次，响应会显示旧值 —— 那是"接口在说谎"）；
     * 其余字段（计数、等级、注册时间）来自实体，它们不受本次更新影响。</p>
     */
    private UserProfileVO toProfileVO(User user, String nickname, String avatarUrl,
                                      String bio, int gender) {
        return new UserProfileVO(
                user.getId(),
                nickname,
                avatarUrl,
                bio,
                gender,
                nullToZero(user.getPostCount()),
                nullToZero(user.getFollowCount()),
                nullToZero(user.getFansCount()),
                nullToZero(user.getLikeReceivedCount()),
                user.getLevel(),
                // 自己看自己：两个关注标志为 false。
                // **不是另定的口径** —— UserService.getProfile 在已登录时就是算
                // isFollowing(me, me) / isFollowedBy(me, me)，而 follow 表不允许自我关注，
                // 因此结果就是 false（与既有路径逐字一致）。
                // 这里刻意不用 null：null 在本项目里的语义是"未登录、未知"，
                // 用它会让前端把"我自己"当成未登录态渲染。
                false,
                false,
                user.getCreatedAt());
    }

    private static int nullToZero(Integer value) {
        return value == null ? 0 : value;
    }
}
