package com.liteark.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("totp_recovery_code")
public class TotpRecoveryCode {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String codeHash;
    private Integer used;
    private String usedAt;
    private String batch;
    private String createdAt;
}
