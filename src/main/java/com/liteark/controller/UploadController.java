package com.liteark.controller;

import com.liteark.common.R;
import com.liteark.service.UploadTaskService;
import com.liteark.util.ClientUtil;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.HashMap;
import java.util.Map;

/** 分片断点续传接口（需求 §3.2 / §4.4） */
@RestController
@RequestMapping("/api/upload")
@RequiredArgsConstructor
public class UploadController extends BaseController {
    private final UploadTaskService uploadService;

    @Data
    public static class InitReq {
        private String md5;
        private String name;
        private Long totalSize;
        private Integer chunkSize;
        private Integer chunkTotal;
        private Long parentId;
    }

    @Data
    public static class IdReq {
        private String uploadId;
    }

    /** 前端按端获取分片/并发配置 */
    @GetMapping("/config")
    public R<Map<String, Object>> config() {
        String client = ClientUtil.clientType(request);
        Map<String, Object> m = new HashMap<>();
        m.put("chunkSize", uploadService.configuredChunkSize(client));
        int concurrency = 2;
        try {
            String key = "Mobile".equalsIgnoreCase(client) ? "upload_concurrency_mobile" : "upload_concurrency_pc";
            concurrency = Integer.parseInt(uploadService.configValue(key));
        } catch (Exception ignored) {}
        m.put("concurrency", concurrency);
        m.put("clientType", client);
        return R.ok(m);
    }

    @PostMapping("/init")
    public R<Map<String, Object>> init(@RequestBody InitReq req) {
        String client = ClientUtil.clientType(request);
        Map<String, Object> r = uploadService.init(currentUser.id(), req.getParentId(), req.getMd5(), req.getName(),
                req.getTotalSize() == null ? 0 : req.getTotalSize(),
                req.getChunkSize() == null ? 0 : req.getChunkSize(),
                req.getChunkTotal() == null ? 0 : req.getChunkTotal(), client);
        String detail = "分 " + req.getChunkTotal() + " 片上传到 " + auditService.dirPath(req.getParentId())
                + "（" + com.liteark.service.AuditService.client(client) + "）";
        audit(Boolean.TRUE.equals(r.get("resumed")) ? "分片续传" : "分片上传开始",
                "文件：" + req.getName(), detail);
        return R.ok(r);
    }

    @PostMapping("/chunk")
    public R<Void> chunk(@RequestParam String uploadId, @RequestParam int index, @RequestParam("chunk") MultipartFile chunk) {
        uploadService.chunk(currentUser.id(), uploadId, index, chunk);
        return R.ok(null);
    }

    @GetMapping("/status")
    public R<Map<String, Object>> status(@RequestParam String uploadId) {
        return R.ok(uploadService.status(currentUser.id(), uploadId));
    }

    @PostMapping("/complete")
    public R<Map<String, Object>> complete(@RequestBody IdReq req) {
        Map<String, Object> r = uploadService.complete(currentUser.id(), req.getUploadId());
        audit("分片上传完成", "文件：" + r.get("name"), "分片合并完成，已存入网盘");
        return R.ok(r, "上传完成");
    }

    @PostMapping("/cancel")
    public R<Void> cancel(@RequestBody IdReq req) {
        uploadService.cancel(currentUser.id(), req.getUploadId());
        audit("分片上传取消", "未完成的上传任务", "已放弃本次上传，文件不会保存");
        return R.ok(null, "任务已取消");
    }
}
