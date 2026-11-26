package com.liteark.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("doc_version")
public class DocVersion {
    @TableId(type = IdType.AUTO)
    private Long id;
    private Long fileId;
    private Integer version;
    private String storageKey;
    private Long size;
    private String createdAt;
}
