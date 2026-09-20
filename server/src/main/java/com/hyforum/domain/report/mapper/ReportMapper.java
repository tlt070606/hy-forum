package com.hyforum.domain.report.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hyforum.domain.report.entity.Report;
import org.apache.ibatis.annotations.Mapper;

/**
 * 举报 Mapper（M5 新增，放 {@code domain} 以符合铁律 3）。
 *
 * <p>本任务只用它做一次 {@code INSERT}（举报入口）与一次 {@code COUNT}（限流复核）。
 * 举报的读取与处理（后台列表、处理/驳回）归 M6，到那时再加查询方法 ——
 * 现在加等于给一个还没有调用方的接口留位置。</p>
 */
@Mapper
public interface ReportMapper extends BaseMapper<Report> {
}
