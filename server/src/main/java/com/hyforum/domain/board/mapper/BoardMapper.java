package com.hyforum.domain.board.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hyforum.domain.board.entity.Board;
import org.apache.ibatis.annotations.Mapper;

/**
 * 版块 Mapper（M3 新增）。
 *
 * <p>只暴露 MyBatis-Plus 的 {@link BaseMapper} 能力，不写自定义 SQL ——
 * 技术方案 §9 要求「禁止字符串拼接 SQL」，参数化查询由 BaseMapper 与
 * LambdaQueryWrapper 保证。</p>
 *
 * <p>归属：{@code com.hyforum.domain}（跨模块共享）。业务模块可以依赖它，
 * 但<b>不得调用别的业务包的 Mapper</b>（铁律 3）—— 这正是 {@code board}
 * 必须放在 {@code domain} 而不是 {@code com.hyforum.post} 的原因：
 * M6 的版块管理接口（{@code CRUD /api/admin/boards}）将来也要用它。</p>
 */
@Mapper
public interface BoardMapper extends BaseMapper<Board> {
}
