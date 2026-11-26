package com.liteark.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 【CHG-12 新增】共享访问日志表 */
@Data
@TableName("share_access_log")
public class ShareAccessLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long shareId;
    private String token;
    private String visitor;
    private String ip;
    private String ua;
    /** PC / Mobile */
    private String clientType;
    private String accessedAt;
}
