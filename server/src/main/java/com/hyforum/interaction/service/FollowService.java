package com.hyforum.interaction.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hyforum.common.api.ErrorCode;
import com.hyforum.common.api.PageResult;
import com.hyforum.common.exception.BizException;
import com.hyforum.domain.interaction.entity.Follow;
import com.hyforum.domain.interaction.mapper.FollowMapper;
import com.hyforum.domain.user.entity.User;
import com.hyforum.domain.user.mapper.UserMapper;
import com.hyforum.interaction.vo.FollowUserVO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 关注服务（docs/技术方案.md §6.7、§8.1、§8.2）。
 *
 * <h2>三条被任务书点名的口径（§5.5）</h2>
 * <ol>
 *   <li><b>禁止关注自己</b>：明确拒绝（{@link BizException} → 400），
 *       不是静默成功；</li>
 *   <li><b>双向计数各 +1 一次</b>：{@code user.follow_count}（我关注的人数）与
 *       {@code user.fans_count}（关注我的人数）。"各只加一次"靠唯一索引
 *       {@code uk_follow} + 捕获 {@link DuplicateKeyException} 保证 ——
 *       重复关注绝不能把计数加两次；</li>
 *   <li><b>计数与关系表同事务</b>：落 {@code follow} 行与两次 {@code UPDATE} 在同一个
 *       {@code @Transactional} 里；递减一律带下限 0（任务书 §5.3：计数不得为负）。</li>
 * </ol>
 *
 * <h2>列名口径</h2>
 * <p>任务书与技术方案里说的是 {@code following_count}／{@code follower_count}，
 * 而 {@code schema.sql} 里的列名是 {@code follow_count}／{@code fans_count}。
 * 契约不可改，因此代码一律以 schema 为准，命名差异已在交付报告中登记。</p>
 */
@Service
public class FollowService {

    private static final Logger log = LoggerFactory.getLogger(FollowService.class);

    private final FollowMapper followMapper;
    private final UserMapper userMapper;

    public FollowService(FollowMapper followMapper, UserMapper userMapper) {
        this.followMapper = followMapper;
        this.userMapper = userMapper;
    }

    /**
     * 关注（§6.7 {@code POST /api/follow/{userId}}）：幂等。
     *
     * <p>顺序：先拒"关注自己"→ 校验目标用户存在 → {@code INSERT}（冲突即幂等返回）
     * → 双侧计数各 +1。</p>
     *
     * <p><b>为什么"关注自己"必须先于插入判定</b>：{@code follow} 表没有
     * {@code CHECK (user_id <> target_user_id)}，所以自我关注在数据库层是<b>合法</b>的
     * —— 不在这里拦住，它就会真的落一行，然后这个人出现在自己的粉丝列表里，
     * 而 {@code fans_count} 与 {@code follow_count} 同时 +1，从对账上完全看不出来。</p>
     *
     * @param userId   当前登录用户 id（关注者）
     * @param targetId 被关注者 id
     */
    @Transactional
    public void follow(long userId, long targetId) {
        if (userId == targetId) {
            // 错误码选 400 而不是 403：这不是"你没有权限"，而是"这个请求本身不成立"。
            // 403 的语义是"你无权对<b>某个存在的对象</b>做这件事"，而这里根本不存在
            // 一个"关注自己"的合法对象。同理不用 404（那会暗示"这个用户不存在"，
            // 而用户明明存在，就是你自己）。选码理由已写入交付报告。
            throw new BizException(ErrorCode.BAD_REQUEST, "不能关注自己");
        }
        User target = userMapper.selectById(targetId);
        if (target == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "用户不存在");
        }

        Follow follow = new Follow();
        follow.setUserId(userId);
        follow.setTargetUserId(targetId);
        follow.setCreatedAt(LocalDateTime.now());
        try {
            followMapper.insert(follow);
        } catch (DuplicateKeyException ex) {
            // uk_follow 拦下重复关注：幂等成功，且**绝不再加计数**（加两次就再也对不上）
            log.debug("重复关注，幂等返回：userId={} targetId={}", userId, targetId);
            return;
        }

        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .setSql("follow_count = follow_count + 1")
                .eq(User::getId, userId));
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .setSql("fans_count = fans_count + 1")
                .eq(User::getId, targetId));
    }

    /**
     * 取消关注（§6.7 {@code DELETE /api/follow/{userId}}）：幂等。
     *
     * <p>以 {@code DELETE} 的影响行数决定是否递减 —— 重复取消返回成功且<b>不动计数</b>。
     * 不做"关注自己"的拒绝：取消一个不存在的自我关注是合法且无副作用的幂等操作，
     * 报错反而会让"我点了取消但页面报错"变成一种用户可见的困惑。</p>
     */
    @Transactional
    public void unfollow(long userId, long targetId) {
        int affected = followMapper.delete(Wrappers.<Follow>lambdaQuery()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getTargetUserId, targetId));
        if (affected == 0) {
            log.debug("取消关注：本来就未关注，幂等返回 userId={} targetId={}", userId, targetId);
            return;
        }
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .setSql("follow_count = IF(follow_count > 0, follow_count - 1, 0)")
                .eq(User::getId, userId));
        userMapper.update(null, Wrappers.<User>lambdaUpdate()
                .setSql("fans_count = IF(fans_count > 0, fans_count - 1, 0)")
                .eq(User::getId, targetId));
    }

    /** 我是否关注了某人（个人主页 {@code isFollowing} 与关注流都会用）。 */
    @Transactional(readOnly = true)
    public boolean isFollowing(long userId, long targetId) {
        return followMapper.selectCount(Wrappers.<Follow>lambdaQuery()
                .eq(Follow::getUserId, userId)
                .eq(Follow::getTargetUserId, targetId)) > 0;
    }

    /** 某人是否关注了我（个人主页 {@code isFollowedBy}）。 */
    @Transactional(readOnly = true)
    public boolean isFollowedBy(long userId, long otherId) {
        return isFollowing(otherId, userId);
    }

    /** 我关注的人的 id 列表（关注流查询的输入）。用户未关注任何人时返回空列表。 */
    @Transactional(readOnly = true)
    public List<Long> followingIds(long userId) {
        return followMapper.selectList(Wrappers.<Follow>lambdaQuery()
                        .eq(Follow::getUserId, userId)
                        .select(Follow::getTargetUserId))
                .stream().map(Follow::getTargetUserId).distinct().toList();
    }

    /**
     * 某人关注的人（§6.7 {@code GET /api/users/{id}/follows}），按关注时间倒序分页。
     *
     * <p>被关注者已被逻辑删除时跳过该行：前台不该出现"已注销用户"的条目，
     * 而且这类行会让列表长度与 {@code follow_count} 对不上 —— 那属于另一类对账，
     * 不属于本接口的展示职责。</p>
     */
    @Transactional(readOnly = true)
    public PageResult<FollowUserVO> listFollowing(long userId, int page, int size) {
        int pageNo = PageResult.normalizePage(page);
        int pageSize = PageResult.normalizeSize(size);
        Page<Follow> query = new Page<>(pageNo, pageSize);
        IPage<Follow> result = followMapper.selectPage(query, Wrappers.<Follow>lambdaQuery()
                .eq(Follow::getUserId, userId)
                .orderByDesc(Follow::getCreatedAt)
                .orderByDesc(Follow::getId));
        return PageResult.of(toUserItems(result.getRecords(), true), result.getTotal(), pageNo, pageSize);
    }

    /** 关注我的人（§6.7 {@code GET /api/users/{id}/fans}），按关注时间倒序分页。 */
    @Transactional(readOnly = true)
    public PageResult<FollowUserVO> listFans(long userId, int page, int size) {
        int pageNo = PageResult.normalizePage(page);
        int pageSize = PageResult.normalizeSize(size);
        Page<Follow> query = new Page<>(pageNo, pageSize);
        IPage<Follow> result = followMapper.selectPage(query, Wrappers.<Follow>lambdaQuery()
                .eq(Follow::getTargetUserId, userId)
                .orderByDesc(Follow::getCreatedAt)
                .orderByDesc(Follow::getId));
        return PageResult.of(toUserItems(result.getRecords(), false), result.getTotal(), pageNo, pageSize);
    }

    /**
     * 关系行 → 列表项（批量取用户，避免 N+1）。
     *
     * @param following true = 取 {@code target_user_id} 那一侧（我关注的人）；
     *                  false = 取 {@code user_id} 那一侧（我的粉丝）
     */
    private List<FollowUserVO> toUserItems(List<Follow> rows, boolean following) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> userIds = rows.stream()
                .map(row -> following ? row.getTargetUserId() : row.getUserId())
                .distinct().toList();
        Map<Long, User> users = new HashMap<>();
        for (User user : userMapper.selectBatchIds(userIds)) {
            users.put(user.getId(), user);
        }
        List<FollowUserVO> items = new ArrayList<>(rows.size());
        for (Follow row : rows) {
            Long id = following ? row.getTargetUserId() : row.getUserId();
            User user = users.get(id);
            if (user == null) {
                continue;   // 用户已被逻辑删除（selectBatchIds 会过滤）→ 跳过
            }
            items.add(new FollowUserVO(user.getId(), user.getNickname(), user.getAvatarUrl(),
                    user.getBio(), row.getCreatedAt()));
        }
        return items;
    }

    /** 关系行的权威条数：{@code user.follow_count} 必须等于它（对账用）。 */
    @Transactional(readOnly = true)
    public long countFollowing(long userId) {
        return followMapper.selectCount(Wrappers.<Follow>lambdaQuery().eq(Follow::getUserId, userId));
    }

    /** {@code user.fans_count} 的权威来源。 */
    @Transactional(readOnly = true)
    public long countFans(long userId) {
        return followMapper.selectCount(Wrappers.<Follow>lambdaQuery().eq(Follow::getTargetUserId, userId));
    }

    /** 供上层做"两个 id 是否同一个人"的判断，避免各处自己写比较。 */
    public static boolean sameUser(Long left, Long right) {
        return Objects.equals(left, right);
    }
}
