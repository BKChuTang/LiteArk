package com.liteark.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

/** 分片续传任务表（V1.1 预留，核心版本建表但不启用分片接口） */
@Data
@TableName("upload_task")
public class UploadTask {
    @TableId(type = IdType.AUTO)
    private Long id;
    private String uploadId;
    private String fileMd5;
    private String fileName;
    private Long totalSize;
    private Integer chunkSize;
    private Integer chunkTotal;
    /** 目标目录 ID */
    private Long parentId;
    private String uploaded;
    private Long ownerId;
    /** PC / Mobile */
    private String clientType;
    private String status;
    private String expireAt;
    private String createdAt;
    private String updatedAt;
}
