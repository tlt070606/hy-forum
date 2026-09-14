package com.hyforum.domain.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hyforum.domain.admin.entity.Admin;
import org.apache.ibatis.annotations.Mapper;

/**
 * 管理员 Mapper（{@code admin} 表）。与 {@code user} 表严格隔离（技术方案 §9）。
 */
@Mapper
public interface AdminMapper extends BaseMapper<Admin> {
}
