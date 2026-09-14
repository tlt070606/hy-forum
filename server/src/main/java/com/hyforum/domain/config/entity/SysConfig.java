package com.hyforum.domain.config.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

import java.time.LocalDateTime;

/**
 * 系统配置实体，对应 {@code sys_config} 表（docs/db/schema.sql 表 13）。
 *
 * <p>首项即 {@code register_mode}（注册模式，技术方案 §8.8）。运行期可调参数一律走本表，
 * <b>不写死在代码或配置文件里</b> —— 这样切换注册模式才不需要发版（技术方案 §8.8 设计要点）。</p>
 */
@TableName("sys_config")
public class SysConfig {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** 配置键，如 {@code register_mode}，唯一（uk_config_key）。 */
    private String configKey;

    /** 配置值，如 {@code open} / {@code invite} / {@code closed}。 */
    private String configValue;

    private String remark;

    private LocalDateTime updatedAt;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getConfigKey() {
        return configKey;
    }

    public void setConfigKey(String configKey) {
        this.configKey = configKey;
    }

    public String getConfigValue() {
        return configValue;
    }

    public void setConfigValue(String configValue) {
        this.configValue = configValue;
    }

    public String getRemark() {
        return remark;
    }

    public void setRemark(String remark) {
        this.remark = remark;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
