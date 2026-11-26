package com.liteark.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("operation_log")
public class OperationLog {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String username;
    private String action;
    private String resource;
    private String detail;
    private String ip;
    /** 【CHG-12】PC / Mobile */
    private String clientType;
    /** 【CHG-12】User-Agent，用于识别移动端 */
    private String ua;
    private String result;
    private String createdAt;
}
