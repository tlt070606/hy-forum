package com.hyforum.domain.config.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hyforum.domain.config.entity.SysConfig;
import org.apache.ibatis.annotations.Mapper;

/**
 * 系统配置 Mapper（{@code sys_config} 表）。
 */
@Mapper
public interface SysConfigMapper extends BaseMapper<SysConfig> {
}
