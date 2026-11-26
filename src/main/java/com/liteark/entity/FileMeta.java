package com.liteark.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("file_meta")
public class FileMeta {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long parentId;
    private Long ownerId;
    private String name;
    private Integer isDir;
    private String ext;
    private Long size;
    private String storageKey;
    private String md5;
    private Integer version;
    private Integer isDeleted;
    private String deletedAt;
    private String createdAt;
    private String updatedAt;
    /** 【CHG-12】缩略图路径，移动端按 ?w= 取对应尺寸 */
    @TableField("thumbnail_path")
    private String thumbnailPath;

    /** 非持久化：当前用户在该项上的有效权限（view/edit/download/upload） */
    @TableField(exist = false)
    private java.util.List<String> perms;

    /** 非持久化：该项来自他人授权时为所有者昵称 */
    @TableField(exist = false)
    private String ownerName;

    /** 非持久化：是否为“共享给我”的他人资源 */
    @TableField(exist = false)
    private Boolean granted;
}
