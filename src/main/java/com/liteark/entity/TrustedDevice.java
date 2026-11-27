package com.liteark.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("trusted_device")
public class TrustedDevice {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;
    private String deviceHash;
    private String deviceName;
    private String ip;
    private String clientType;
    private String createdAt;
    private String expireAt;
}
