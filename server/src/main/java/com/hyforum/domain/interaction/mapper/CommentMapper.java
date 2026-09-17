package com.hyforum.domain.interaction.mapper;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.hyforum.domain.interaction.entity.Comment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.MapKey;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

/**
 * 评论 Mapper（M4 新增，放 {@code domain} 以符合铁律 3：{@code interaction} 与
 * {@code user} 两个包都要读评论，而它们之间/对 {@code post} 包都不得互相依赖）。
 *
 * <p>自定义 SQL 只有下面两条 <b>"按主楼分组"</b> 的聚合查询。为什么值得自定义：</p>
 * <ul>
 *   <li>主楼列表要给每条主楼带 {@code reply_count} 与<b>前 2 条</b>楼中楼预览；
 *       若按"每条主楼各查一次"写就是 N+1（一页 20 条 = 41 次往返），
 *       在 2 核 2G 的单机上这是能感觉到的慢；</li>
 *   <li>把"取前 2 条"交给 SQL 而不是"查全部再在内存里截断"：后者在热门帖
 *       （单主楼几百条楼中楼）会把整棵树拉回应用进程，而它们一条都不会被展示。</li>
 * </ul>
 *
 * <p>两条 SQL 都<b>不含任何字符串拼接</b>，主楼 id 列表一律走 {@code #{}} 占位
 * （技术方案 §9 禁的是把参数拼进 SQL）。</p>
 */
@Mapper
public interface CommentMapper extends BaseMapper<Comment> {

    /**
     * 按主楼分组统计楼中楼数量（服务端二次核对用；权威值仍是主楼行上的
     * {@code reply_count} 冗余计数 —— 本方法用于<b>断言两者一致</b>，不用于展示）。
     *
     * <p>只统计正常可见的楼中楼：屏蔽与逻辑删除的楼中楼不该出现在计数里，
     * 否则用户看到"3 条回复"却点进去只有 1 条。</p>
     *
     * <p>刻意<b>不加</b> {@code parent_id <> 0} 条件：主楼自己的 {@code root_id} 是 0，
     * 而 0 不在入参 id 列表里（主楼 id 都 &gt; 0），因此 {@code root_id IN (...)}
     * 天然只命中楼中楼。多加一个条件只会让"计数口径"与 {@code countAliveReplies}
     * 有出现分歧的机会。</p>
     *
     * @param rootIds 主楼 id 列表（非空由调用方保证）
     * @return 每行 {@code (rootId, cnt)}；没有楼中楼的主楼<b>不会出现</b>在结果里
     */
    @Select("<script>"
            + "SELECT root_id AS rootId, COUNT(*) AS cnt FROM comment "
            + "WHERE root_id IN "
            + "<foreach collection='rootIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + " AND status = 1 AND is_deleted = 0 "
            + "GROUP BY root_id"
            + "</script>")
    List<ReplyCountRow> countRepliesByRoots(@Param("rootIds") List<Long> rootIds);

    /**
     * 每条主楼的<b>最近 2 条</b>楼中楼 id（契约 §6.6：主楼列表带前 2 条预览）。
     *
     * <p>返回**倒序**的 id（新的在前），调用方按时间升序重排后才是展示顺序 ——
     * 因为 {@code ORDER BY created_at DESC LIMIT 2} 取到的是"最新两条"，
     * 若改成 {@code ASC LIMIT 2} 取到的是"最早两条"，那是另一种业务含义。</p>
     *
     * <p><b>为什么用窗口函数 {@code ROW_NUMBER()} 而不是相关子查询</b>
     * （这一版是踩坑后重写的，写清楚免得以后有人"顺手简化"回去）：
     * 最直觉的写法是 {@code ... AND c.id IN (SELECT c2.id ... ORDER BY ... LIMIT 2)}，
     * 但 <b>MySQL 8.0 明确不支持 {@code IN/ALL/ANY/SOME} 子查询里带 {@code LIMIT}</b>，
     * 报错原文：</p>
     * <pre>
     *   java.sql.SQLSyntaxErrorException:
     *   This version of MySQL doesn't yet support 'LIMIT &amp; IN/ALL/ANY/SOME subquery'
     * </pre>
     * <p>而它的表现是接口 <b>500</b>（走全局兜底码），从响应体上完全看不出是 SQL 形态问题。
     * 窗口函数一次扫描就能按 {@code root_id} 分组编号并筛出前 2，既正确又只用一个子查询。</p>
     *
     * <p>{@code ORDER BY created_at DESC, id DESC} 里的 {@code id} 不是装饰：
     * {@code created_at} 的精度是秒，同一秒插入的两条楼中楼必须有确定的先后，
     * 否则"前 2 条是哪 2 条"会随执行计划变，用例偶发红。</p>
     *
     * @param rootIds 主楼 id 列表
     * @return 每行 {@code (rootId, replyId)}，同一主楼最多 2 行
     */
    @Select("<script>"
            + "SELECT t.rootId AS rootId, t.replyId AS replyId FROM ("
            + "  SELECT c.root_id AS rootId, c.id AS replyId,"
            + "         ROW_NUMBER() OVER (PARTITION BY c.root_id ORDER BY c.created_at DESC, c.id DESC) AS rn"
            + "  FROM comment c WHERE c.root_id IN "
            + "<foreach collection='rootIds' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "    AND c.parent_id &lt;&gt; 0 AND c.status = 1 AND c.is_deleted = 0"
            + ") t WHERE t.rn &lt;= 2 ORDER BY t.rootId, t.replyId"
            + "</script>")
    List<PreviewRow> findPreviewReplyIds(@Param("rootIds") List<Long> rootIds);

    /**
     * 按 id 批量取评论（含 {@code is_deleted=0} 过滤，由 MyBatis-Plus 自动附加）。
     *
     * <p>刻意包一层而不是让调用方到处 {@code selectBatchIds}：把"逻辑删除要过滤"
     * 这件事收在一个地方，避免将来有人漏了它。</p>
     */
    default List<Comment> selectAliveByIds(List<Long> ids) {
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        QueryWrapper<Comment> wrapper = Wrappers.query();
        wrapper.in("id", ids);
        return selectList(wrapper);
    }

    /** 某条主楼下的楼中楼数量（只算正常可见的），用于一致性断言与删除路径的复核。 */
    default long countAliveReplies(long rootId) {
        QueryWrapper<Comment> wrapper = Wrappers.query();
        wrapper.eq("root_id", rootId);
        Long count = selectCount(wrapper);
        return count == null ? 0L : count;
    }

    /**
     * 主楼 id → 楼中楼数量。
     *
     * <p>用 record 作返回类型：MyBatis 3.5.x 支持按<b>构造器参数名</b>匹配结果列
     * （编译时带 {@code -parameters}，Spring Boot 的父 POM 默认开启），
     * 因此别名必须与参数名逐字一致（{@code rootId} / {@code cnt}）。</p>
     */
    record ReplyCountRow(Long rootId, Long cnt) {
    }

    /** 主楼 id + 预览楼中楼 id。 */
    record PreviewRow(Long rootId, Long replyId) {
    }
}
