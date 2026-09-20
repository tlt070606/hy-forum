package com.hyforum.domain.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hyforum.domain.admin.entity.AdminOperationLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * 管理操作留痕 Mapper（M5 新增，放 {@code domain} 以符合铁律 3）。
 *
 * <p><b>刻意没有任何自定义 SQL，也没有逻辑删除</b>：本表只增不改不删（合规 C9）。
 * 需要读它的时候（后台"操作日志"页面属 M6）用 BaseMapper 的 Wrapper 即可。</p>
 */
@Mapper
public interface AdminOperationLogMapper extends BaseMapper<AdminOperationLog> {
}
