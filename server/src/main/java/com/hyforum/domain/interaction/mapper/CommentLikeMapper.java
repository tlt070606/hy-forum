package com.hyforum.domain.interaction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hyforum.domain.interaction.entity.CommentLike;
import org.apache.ibatis.annotations.Mapper;

/**
 * 评论点赞 Mapper（M4 新增）。
 *
 * <p>幂等由 {@code uk_comment_user} 保证；取消点赞同样以 {@code delete} 的影响行数
 * 决定是否递减 {@code comment.like_count}。理由见 {@link PostLikeMapper}。</p>
 */
@Mapper
public interface CommentLikeMapper extends BaseMapper<CommentLike> {
}
