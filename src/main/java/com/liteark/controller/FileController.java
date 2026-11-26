package com.liteark.controller;

import com.liteark.common.BizException;
import com.liteark.common.R;
import com.liteark.entity.DocVersion;
import com.liteark.entity.FileMeta;
import com.liteark.entity.SystemConfig;
import com.liteark.mapper.DocVersionMapper;
import com.liteark.mapper.FileMetaMapper;
import com.liteark.mapper.SystemConfigMapper;
import com.liteark.service.AuditService;
import com.liteark.service.FileService;
import com.liteark.service.OfficeHtmlService;
import com.liteark.service.OfficePreviewService;
import com.liteark.service.PermissionService;
import com.liteark.service.ThumbnailService;
import com.liteark.util.ClientUtil;
import com.liteark.util.FileResponses;
import com.liteark.util.FileUtil;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.core.io.UrlResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import javax.servlet.http.HttpServletRequest;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController extends BaseController {
    private final FileService fileService;
    private final PermissionService permService;
    private final ThumbnailService thumbService;
    private final OfficePreviewService officeService;
    private final OfficeHtmlService officeHtmlService;
    private final SystemConfigMapper configMapper;
    private final FileMetaMapper fileMapper;
    private final DocVersionMapper versionMapper;

    @Data
    public static class IdsReq {
        private List<Long> ids;
        private Long targetId;
    }

    @GetMapping("/tree")
    public R<List<Map<String, Object>>> tree() {
        return R.ok(fileService.tree(currentUser.id()));
    }

    @GetMapping("/list")
    public R<List<FileMeta>> list(@RequestParam(defaultValue = "0") Long parentId,
                                  @RequestParam(required = false) String keyword) {
        Long pid = parentId == null ? 0L : parentId;
        permService.require(currentUser.id(), pid, "view");
        return R.ok(fileService.list(currentUser.id(), pid, keyword));
    }

    /** 当前所在文件夹的权限与归属信息，前端据此收放「上传 / 新建文件夹」并展示共享横幅 */
    @GetMapping("/folder-info")
    public R<Map<String, Object>> folderInfo(@RequestParam(defaultValue = "0") Long folderId) {
        return R.ok(fileService.folderInfo(currentUser.id(), folderId));
    }

    @GetMapping("/breadcrumb")
    public R<List<Map<String, Object>>> breadcrumb(@RequestParam(defaultValue = "0") Long id) {
        return R.ok(fileService.breadcrumb(currentUser.id(), id));
    }

    @PostMapping("/folder")
    public R<Map<String, Object>> createFolder(@RequestBody Map<String, Object> body) {
        Long parent = body.get("parent_id") == null ? 0L : Long.valueOf(body.get("parent_id").toString());
        permService.require(currentUser.id(), parent, "upload");
        Long id = fileService.createFolder(currentUser.id(), parent, String.valueOf(body.get("name")));
        audit("新建文件夹", "文件夹：" + body.get("name"), "位置：" + auditService.dirPath(parent));
        Map<String, Object> m = new HashMap<>();
        m.put("id", id);
        return R.ok(m, "创建成功");
    }

    @PutMapping("/{id}/rename")
    public R<Void> rename(@PathVariable Long id, @RequestBody Map<String, String> body) {
        FileMeta renaming = permService.requireFile(currentUser.id(), id, "edit");
        fileService.rename(currentUser.id(), id, body.get("name"));
        audit("重命名", auditService.fileLabel(id),
                "原名「" + renaming.getName() + "」改为「" + body.get("name") + "」");
        return R.ok(null, "重命名成功");
    }

    @PostMapping("/copy")
    public R<Map<String, Object>> copy(@RequestBody IdsReq req) {
        Long dest = req.getTargetId() == null ? 0L : req.getTargetId();
        permService.require(currentUser.id(), dest, "upload");
        if (req.getIds() != null) for (Long id : req.getIds()) permService.requireFile(currentUser.id(), id, "view");
        int n = fileService.copy(currentUser.id(), req.getIds(), req.getTargetId() == null ? 0L : req.getTargetId());
        audit("复制", n + " 个项目", "复制到：" + auditService.dirPath(dest));
        return R.ok(Collections.singletonMap("copied", n), "已复制 " + n + " 项");
    }

    @PostMapping("/move")
    public R<Map<String, Object>> move(@RequestBody IdsReq req) {
        Long dest = req.getTargetId() == null ? 0L : req.getTargetId();
        permService.require(currentUser.id(), dest, "upload");
        if (req.getIds() != null) for (Long id : req.getIds()) permService.requireFile(currentUser.id(), id, "delete");
        int n = fileService.move(currentUser.id(), req.getIds(), req.getTargetId() == null ? 0L : req.getTargetId());
        audit("剪切", n + " 个项目", "移动到：" + auditService.dirPath(dest));
        return R.ok(Collections.singletonMap("moved", n), "已移动 " + n + " 项");
    }

    @DeleteMapping
    public R<Void> recycle(@RequestParam String ids) {
        List<Long> list = parseIds(ids);
        if (list.isEmpty()) throw new BizException(400, "未选择项目");
        for (Long id : list) permService.requireFile(currentUser.id(), id, "delete");
        String deleted = auditService.briefNames(list);
        fileService.recycle(currentUser.id(), list);
        audit("删除到回收站", deleted, "可在回收站还原");
        return R.ok(null, "已移入回收站");
    }

    @GetMapping("/recycle")
    public R<List<FileMeta>> recycleList() {
        return R.ok(fileService.recycleList(currentUser.id()));
    }

    @PostMapping("/{id}/restore")
    public R<Void> restore(@PathVariable Long id) {
        // 回收站内的项已标记删除，权限由 service 按上级目录校验，这里不能再用 requireFile
        String restored = auditService.fileLabel(id);
        fileService.restore(currentUser.id(), id);
        audit("还原", restored, "已从回收站还原到原位置");
        return R.ok(null, "已还原");
    }

    @DeleteMapping("/{id}/permanent")
    public R<Void> permanentDelete(@PathVariable Long id) {
        // 同上：已删除项由 service 按所有权校验
        String purged = auditService.fileLabel(id);
        fileService.permanentDelete(currentUser.id(), id);
        audit("永久删除", purged, "已彻底删除，不可恢复");
        return R.ok(null, "已永久删除");
    }

    @DeleteMapping("/recycle/clear")
    public R<Void> clearRecycle() {
        int cleared = fileService.recycleList(currentUser.id()).size();
        fileService.clearRecycle(currentUser.id());
        audit("清空回收站", cleared == 0 ? "回收站（空）" : cleared + " 个项目", "已全部彻底删除，不可恢复");
        return R.ok(null, "回收站已清空");
    }

    @PostMapping("/upload")
    public R<List<Map<String, Object>>> upload(@RequestParam("files") List<MultipartFile> files,
                                               @RequestParam(defaultValue = "0") Long parentId,
                                               @RequestHeader(value = "X-Client-Type", defaultValue = "PC") String clientType) {
        permService.require(currentUser.id(), parentId, "upload");
        List<Map<String, Object>> res = fileService.upload(currentUser.id(), parentId, files, clientType);
        List<String> uploaded = new ArrayList<>();
        for (MultipartFile f : files) uploaded.add(f.getOriginalFilename());
        audit("上传", AuditService.briefList(uploaded),
                "上传到 " + auditService.dirPath(parentId) + "（" + AuditService.client(clientType) + "）");
        return R.ok(res, "上传完成");
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<Resource> download(@PathVariable Long id, HttpServletRequest req) {
        FileMeta f = permService.requireFile(currentUser.id(), id, "download");
        if (f.getIsDir() != null && f.getIsDir() == 1) throw new BizException(400, "文件夹请使用批量下载");
        Path path = fileService.physicalPath(f);
        if (!Files.exists(path)) throw new BizException(404, "文件实体丢失");
        boolean mine = f.getOwnerId().equals(currentUser.id());
        audit("下载", "文件：" + f.getName(),
                mine ? "下载自己目录中的文件" : "来自 " + auditService.userName(f.getOwnerId()) + " 授权共享的目录");
        return FileResponses.build(path, f.getName(), false, req);
    }

    @GetMapping("/{id}/preview")
    public ResponseEntity<Resource> preview(@PathVariable Long id, HttpServletRequest req) {
        FileMeta f = permService.requireFile(currentUser.id(), id, "view");
        if (f.getIsDir() != null && f.getIsDir() == 1) throw new BizException(400, "文件夹不支持预览");
        Path path = fileService.physicalPath(f);
        if (!Files.exists(path)) throw new BizException(404, "文件实体丢失");
        return FileResponses.build(path, f.getName(), true, req);
    }

    /** 图片缩略图：移动端按 ?w=200 请求，避免下载原图；生成失败时回退原图，保证列表不出现破图 */
    @GetMapping("/{id}/thumbnail")
    public ResponseEntity<Resource> thumbnail(@PathVariable Long id,
                                       @RequestParam(defaultValue = "200") int w,
                                       HttpServletRequest req) {
        FileMeta f = permService.requireFile(currentUser.id(), id, "view");
        Path thumb = thumbService.get(f.getOwnerId(), f, w);
        if (thumb != null) return FileResponses.build(thumb, "thumbnail.jpg", true, req);
        // webp/svg 等 ImageIO 无法解码的格式，直接回退原文件内联展示
        Path origin = fileService.physicalPath(f);
        if (!Files.exists(origin)) throw new BizException(404, "缩略图不可用");
        return FileResponses.build(origin, f.getName(), true, req);
    }

    /**
     * Office 只读预览：
     * 1) 服务器有 LibreOffice → 转 PDF（高保真，保留版式）；
     * 2) 没有 LibreOffice → 用 POI 解析 docx/xlsx/pptx 渲染只读 HTML（能看内容，不依赖外部程序）；
     * 3) 都不支持（旧版 doc/xls/ppt 且无 LibreOffice）→ 返回明确提示，前端引导下载。
     */
    @GetMapping("/{id}/office-preview")
    public ResponseEntity<Resource> officePreview(@PathVariable Long id, HttpServletRequest req) {
        FileMeta f = officeFile(id);
        Path source = fileService.physicalPath(f);
        if (!Files.exists(source)) throw new BizException(404, "文件实体丢失");
        if (!officeService.enabled()) throw new BizException(503, "Office 在线预览已在系统配置中关闭，请下载后查看");
        String ext = extOf(f);
        boolean libre = officeService.binary() != null;
        // xlsx 固定走 POI 渲染的带页签 HTML：LibreOffice 转 PDF 出来的是一张张纸，没有工作表页签，
        // 多工作表时用户容易只看到第一个表。POI 解析失败（超大文件等）再回退 PDF。
        if (OfficeHtmlService.supported(ext) && ("xlsx".equals(ext) || !libre)) {
            try {
                Path html = officeHtmlService.render(source, f.getName(), ext, cacheKey(f));
                return FileResponses.build(html, "preview.html", true, req);
            } catch (BizException e) {
                if (!libre) throw e;
            }
        }
        if (libre) {
            Path pdf = officeService.convert(source, cacheKey(f));
            return FileResponses.build(pdf, "preview.pdf", true, req);
        }
        throw new BizException(503, "服务器未安装 LibreOffice，旧版 ." + ext + " 暂不支持在线预览，请下载后查看");
    }

    /** 预览能力探测：前端据此决定直接内嵌还是显示提示，避免 iframe 里出现一坨 JSON */
    @GetMapping("/{id}/office-preview/info")
    public R<Map<String, Object>> officePreviewInfo(@PathVariable Long id) {
        FileMeta f = officeFile(id);
        String ext = extOf(f);
        Map<String, Object> info = new LinkedHashMap<>();
        boolean libre = officeService.binary() != null;
        if (!officeService.enabled()) {
            info.put("available", false);
            info.put("mode", "none");
            info.put("msg", "Office 在线预览已在系统配置中关闭，请下载后查看");
        } else if (OfficeHtmlService.supported(ext) && ("xlsx".equals(ext) || !libre)) {
            info.put("available", true);
            info.put("mode", "html");
            info.put("tabs", "xlsx".equals(ext));
        } else if (libre) {
            info.put("available", true);
            info.put("mode", "pdf");
        } else {
            info.put("available", false);
            info.put("mode", "none");
            info.put("msg", "服务器未安装 LibreOffice，该格式（" + ext + "）暂不支持在线预览，请下载后查看");
        }
        return R.ok(info);
    }

    private FileMeta officeFile(Long id) {
        FileMeta f = permService.requireFile(currentUser.id(), id, "view");
        if (f.getIsDir() != null && f.getIsDir() == 1) throw new BizException(400, "文件夹不支持预览");
        return f;
    }

    private static String extOf(FileMeta f) {
        String ext = f.getExt();
        return ext == null || ext.isEmpty() ? FileUtil.ext(f.getName()) : ext.toLowerCase();
    }

    private static String cacheKey(FileMeta f) {
        return f.getMd5() == null || f.getMd5().isEmpty() ? f.getStorageKey() : f.getMd5();
    }

    @RequestMapping(value = "/{id}/save-text", method = {RequestMethod.PUT, RequestMethod.POST},
            consumes = MediaType.TEXT_PLAIN_VALUE)
    public R<Map<String, Object>> saveText(@PathVariable Long id, @RequestBody String content) {
        permService.requireFile(currentUser.id(), id, "edit");
        SystemConfig editCfg = configMapper.selectById("online_edit_enabled");
        if (editCfg != null && "false".equalsIgnoreCase(editCfg.getConfigValue())) {
            throw new BizException(403, "在线编辑已在系统配置中关闭");
        }
        FileMeta ef = fileMapper.selectById(id);
        SystemConfig white = configMapper.selectById("edit_ext_whitelist");
        String whitelist = white == null || white.getConfigValue() == null ? "txt,md,csv,json,html" : white.getConfigValue();
        if (ef == null || !Arrays.asList(whitelist.split(",")).contains(ef.getExt())) {
            throw new BizException(403, "该文件类型不在编辑白名单内");
        }
        int v = fileService.saveText(currentUser.id(), id, content);
        audit("在线编辑", auditService.fileLabel(id), "已保存为第 " + v + " 个版本");
        return R.ok(Collections.singletonMap("version", v), "已保存");
    }

    @GetMapping("/{id}/versions")
    public R<List<DocVersion>> versions(@PathVariable Long id) {
        permService.requireFile(currentUser.id(), id, "view");
        return R.ok(fileService.versions(currentUser.id(), id));
    }

    @PostMapping("/{id}/versions/{versionId}/rollback")
    public R<Map<String, Object>> rollback(@PathVariable Long id, @PathVariable Long versionId) {
        permService.requireFile(currentUser.id(), id, "edit");
        int v = fileService.rollback(currentUser.id(), id, versionId);
        audit("版本回滚", auditService.fileLabel(id), "回退到历史版本，当前为第 " + v + " 个版本");
        return R.ok(Collections.singletonMap("version", v), "已回滚，当前版本 v" + v);
    }

    @PostMapping("/download-zip")
    public ResponseEntity<byte[]> downloadZip(@RequestBody IdsReq req) {
        if (req.getIds() == null || req.getIds().isEmpty()) throw new BizException("未选择文件");
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        // 显式指定 UTF-8：ZipOutputStream 会写入 EFS(0x800) 标记位，Windows/macOS 解压中文名不乱码
        try (ZipOutputStream zip = new ZipOutputStream(bos, StandardCharsets.UTF_8)) {
            for (Long id : req.getIds()) addToZip(id, "", zip);
            zip.finish();
        } catch (Exception e) {
            throw new BizException("打包失败: " + e.getMessage());
        }
        audit("批量下载", auditService.briefNames(req.getIds()), "打包为 ZIP 下载");
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=liteark.zip")
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .body(bos.toByteArray());
    }

    private void addToZip(Long id, String prefix, ZipOutputStream zip) throws Exception {
        FileMeta f = fileMapper.selectById(id);
        if (f == null || (f.getIsDeleted() != null && f.getIsDeleted() == 1)) return;
        // 无下载权限的项跳过而不是整体失败，保证“能下的先下”
        if (!permService.canAccess(currentUser.id(), f, "download")) return;
        if (f.getIsDir() == 1) {
            List<FileMeta> children = fileService.list(currentUser.id(), f.getId(), null);
            for (FileMeta c : children) addToZip(c.getId(), prefix + f.getName() + "/", zip);
        } else {
            Path p = fileService.physicalPath(f);
            if (Files.exists(p)) {
                zip.putNextEntry(new ZipEntry(prefix + f.getName()));
                Files.copy(p, zip);
                zip.closeEntry();
            }
        }
    }

    private List<Long> parseIds(String ids) {
        List<Long> list = new ArrayList<>();
        for (String s : ids.split(",")) {
            if (!s.trim().isEmpty()) list.add(Long.valueOf(s.trim()));
        }
        return list;
    }
}
