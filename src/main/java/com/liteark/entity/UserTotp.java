package com.liteark.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("user_totp")
public class UserTotp {
    @TableId(value = "user_id", type = IdType.INPUT)
    private Long userId;
    private String secretCipher;
    private Integer enabled;
    private Integer failedCount;
    private String lockedUntil;
    private Long lastUsedStep;
    private String createdAt;
    private String updatedAt;
}
