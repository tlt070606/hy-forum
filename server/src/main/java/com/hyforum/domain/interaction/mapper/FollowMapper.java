package com.hyforum.domain.interaction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hyforum.domain.interaction.entity.Follow;
import org.apache.ibatis.annotations.Mapper;

/**
 * 关注关系 Mapper（M4 新增）。
 *
 * <p>幂等由 {@code uk_follow(user_id, target_user_id)} 保证。两条查询路径各有索引：
 * 「我关注了谁」走 {@code uk_follow} 的前缀，「谁关注了我 / 关注流」走
 * {@code idx_target(target_user_id, created_at)}。</p>
 *
 * <p>关注成功后要<b>同事务</b>维护两侧计数（{@code user.follow_count} 与
 * {@code user.fans_count}），各只加一次 —— 这就是 M4 的
 * {@code M4_follow_idempotent} 断言的核心。</p>
 */
@Mapper
public interface FollowMapper extends BaseMapper<Follow> {
}
