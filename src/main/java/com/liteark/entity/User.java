package com.liteark.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("user")
public class User {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String username;
    private String password;
    private String nickname;
    private Long roleId;
    private Long quota;
    private Long used;
    private Integer status;
    private String createdAt;
    private String lastLogin;
    /** 管理员维护的单用户二次验证开通权限；1 允许，0 禁止 */
    private Integer totpAllowed = 1;
}
