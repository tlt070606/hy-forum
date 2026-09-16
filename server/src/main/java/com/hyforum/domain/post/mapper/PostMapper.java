package com.hyforum.domain.post.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hyforum.domain.post.entity.Post;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 帖子 Mapper（M3 新增）。
 *
 * <p>归属 {@code com.hyforum.domain}（跨模块共享）：M4 的评论/点赞/收藏都要按帖子 id 读它，
 * 而铁律 3 禁止 {@code interaction} 包调用 {@code post} 包的 Mapper ——
 * 因此实体与 Mapper 只能放在 {@code domain}。</p>
 *
 * <p><b>关于 {@link #addViewCount}：这是本 Mapper 唯一的自定义 SQL。</b>
 * 为什么不能只用 BaseMapper：浏览量回写是"累加"语义
 * （{@code view_count = view_count + delta}），若写成"先 SELECT 再 UPDATE"，
 * 并发回写会互相覆盖（§8.2 明文要求计数字段只做增减运算）。
 * SQL 里<b>没有任何字符串拼接</b>，两个参数一律走 {@code #{}} 占位（技术方案 §9）。</p>
 */
@Mapper
public interface PostMapper extends BaseMapper<Post> {

    /**
     * 把 Redis 里累积的浏览量增量批量回写到数据库（技术方案 §7 / §8.3）。
     *
     * @param postId 帖子 id
     * @param delta  本批次的增量（&gt; 0）
     * @return 影响行数（帖子被物理删除时为 0，属正常情况）
     */
    @Update("UPDATE post SET view_count = view_count + #{delta} WHERE id = #{postId}")
    int addViewCount(@Param("postId") Long postId, @Param("delta") long delta);
}
