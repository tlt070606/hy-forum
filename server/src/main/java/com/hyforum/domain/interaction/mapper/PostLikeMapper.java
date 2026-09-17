package com.hyforum.domain.interaction.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hyforum.domain.interaction.entity.PostLike;
import org.apache.ibatis.annotations.Mapper;

/**
 * 帖子点赞 Mapper（M4 新增）。
 *
 * <p><b>刻意没有自定义 SQL</b>：点赞/取消点赞就是一次 {@code INSERT} / {@code DELETE}，
 * 幂等由唯一索引 {@code uk_post_user} 保证（技术方案 §8.1）。
 * 任何"先查再写"的辅助方法都不该出现在这里 —— 那正是 P1-4 禁止的写法。</p>
 *
 * <p>唯一注意点：取消点赞必须取 {@code delete(...)} 的<b>返回行数</b>，
 * 由它决定要不要递减 {@code post.like_count}（影响行数=0 表示本来就未点赞，
 * 此时<b>绝不能</b>递减，否则计数会被"重复取消"打穿）。</p>
 */
@Mapper
public interface PostLikeMapper extends BaseMapper<PostLike> {
}
