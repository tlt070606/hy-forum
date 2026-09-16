package com.hyforum.domain.post.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hyforum.domain.post.entity.PostImage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 帖子图片 Mapper（M3 新增）。
 *
 * <p>归属 {@code com.hyforum.domain}（跨模块共享）：M4 的帖子详情要带图，
 * M5/M6 的图片审核出口（置 1／2，见 CR-006）也要用它。</p>
 */
@Mapper
public interface PostImageMapper extends BaseMapper<PostImage> {
}
