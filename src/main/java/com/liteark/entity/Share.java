package com.liteark.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("share")
public class Share {
    @TableId(type = IdType.AUTO)
    private Long id;
    /** 【CHG-12】对外随机 token，与内部 ID 解耦，防枚举 */
    private String token;
    private Long fileId;
    private Long ownerId;
    private String permission;
    private String password;
    private String expiresAt;
    private String createdAt;
    private Integer revoked;
    /** 0 = 不限次数；>0 达到后自动失效 */
    private Integer maxDownloads;
    private Integer downloadCount;
    /** external = 外部分享（任何人凭链接）；internal = 仅名单内的内部用户可访问 */
    private String shareType;
    /** 内部分享的目标用户 ID，逗号分隔；外部分享为空 */
    private String targetUserIds;
    /** 仅列表展示用，不落库 */
    @TableField(exist = false)
    private String fileName;
}
