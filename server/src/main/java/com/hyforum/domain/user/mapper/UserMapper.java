package com.hyforum.domain.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hyforum.domain.user.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper。
 *
 * <p>只暴露 MyBatis-Plus 的 {@link BaseMapper} 能力，不写自定义 SQL ——
 * 技术方案 §9 要求「禁止字符串拼接 SQL」，参数化查询由 BaseMapper 与
 * LambdaQueryWrapper 保证。</p>
 *
 * <p>归属：{@code com.hyforum.domain}（跨模块共享）。铁律 3 允许各业务模块依赖 domain，
 * 但<b>禁止一个业务模块调用另一个业务模块的 Mapper</b> —— 因此所有跨模块需要的数据访问
 * 都必须收敛到本包。</p>
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
