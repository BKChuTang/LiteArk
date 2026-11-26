package com.liteark.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 细粒度资源权限（V1.1 预留，核心版本建表但不接入） */
@Data
@TableName("permission")
public class Permission {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** user | role */
    private String subjectType;
    private Long subjectId;
    private Long folderId;
    /** 权限位；当 sourceRoleId 非空时，同时作为“授权上限”（实际权限 = 角色默认权限 ∩ 本字段） */
    private String permBits;
    private Integer inherit;
    /**
     * 用户规则引用角色时的来源角色 ID。
     * 为空表示直接指定权限；非空表示该规则按角色默认权限实时计算，角色调整后自动跟随。
     */
    private Long sourceRoleId;
}
