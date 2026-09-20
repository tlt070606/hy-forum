package com.hyforum.domain.notify.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.hyforum.domain.notify.entity.Notification;
import org.apache.ibatis.annotations.Mapper;

/**
 * 通知 Mapper（M5 新增，放 {@code domain} 以符合铁律 3）。
 *
 * <p><b>刻意没有自定义 SQL</b>：本表的两种访问都很直白 ——</p>
 * <ul>
 *   <li>未读数：{@code COUNT WHERE user_id = ? AND is_read = 0}（走
 *       {@code idx_user_read(user_id, is_read, created_at DESC)} 的前两列）；</li>
 *   <li>列表：{@code WHERE user_id = ?} 按 {@code created_at DESC} 分页
 *       （同一索引的第 1、3 列）。</li>
 * </ul>
 * <p>都能用 BaseMapper 的 Wrapper 表达，加自定义 SQL 只会多一个需要维护的地方。</p>
 */
@Mapper
public interface NotificationMapper extends BaseMapper<Notification> {
}
