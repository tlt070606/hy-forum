package com.hyforum.domain.interaction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hyforum.domain.interaction.entity.PostCollect;
import org.apache.ibatis.annotations.Mapper;

/**
 * 帖子收藏 Mapper（M4 新增）。
 *
 * <p>幂等由 {@code uk_post_user} 保证（与 {@code post_like} 结构完全一致，§8.1）。
 * 与点赞的唯一差别在<b>读</b>：收藏有"我的收藏列表"这个入口
 * （{@code GET /api/user/collections}），要用 {@code idx_user(user_id, created_at)}
 * 按时间倒序分页 —— 那条索引就是为它建的。</p>
 */
@Mapper
public interface PostCollectMapper extends BaseMapper<PostCollect> {
}
