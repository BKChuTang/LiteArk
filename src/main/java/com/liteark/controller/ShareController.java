package com.liteark.controller;

import com.liteark.common.BizException;
import com.liteark.common.R;
import com.liteark.entity.FileMeta;
import com.liteark.entity.Share;
import com.liteark.entity.ShareAccessLog;
import com.liteark.mapper.FileMetaMapper;
import com.liteark.mapper.UserMapper;
import com.liteark.service.OfficeHtmlService;
import com.liteark.service.OfficePreviewService;
import com.liteark.service.PermissionService;
import com.liteark.service.ShareService;
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

import javax.servlet.http.HttpServletRequest;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@RestController
@RequiredArgsConstructor
public class ShareController extends BaseController {
    private final ShareService shareService;
    private final PermissionService permService;
    private final OfficePreviewService officeService;
    private final OfficeHtmlService officeHtmlService;
    private final FileMetaMapper fileMapper;
    private final UserMapper userMapper;
    private final com.liteark.mapper.ShareMapper shareMapper;
    private final FileUtil fileUtil;

    @Data
    public static class ShareReq {
        private Long fileId;
        private String permission;
        private String password;
        private Integer expireDays;
        private Integer maxDownloads;
        /** external = 外部分享（默认）；internal = 仅名单内内部用户 */
        private String shareType;
        private List<Long> targetIds;
    }

    @Data
    public static class UpdateReq {
        private String permission;
        private String password;
        private Integer expireDays;
        private Integer maxDownloads;
        private String shareType;
        private List<Long> targetIds;
    }

    // ===== 需登录：管理我的共享 =====

    @GetMapping("/api/shares")
    public R<List<Share>> myShares() {
        List<Share> list = shareService.myList(currentUser.id());
        for (Share s : list) {
            FileMeta f = fileMapper.selectById(s.getFileId());
            s.setFileName(f == null ? "（文件已删除）" : f.getName());
        }
        return R.ok(list);
    }

    /** 内部分享可选用户（排除自己、管理员与已禁用账号） */
    @GetMapping("/api/shares/users")
    public R<List<Map<String, Object>>> users() {
        Long me = currentUser.id();
        List<Map<String, Object>> out = new ArrayList<>();
        for (com.liteark.entity.User u : userMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<com.liteark.entity.User>()
                .eq(com.liteark.entity.User::getStatus, 1).orderByAsc(com.liteark.entity.User::getUsername))) {
            if (u.getId().equals(me) || (u.getRoleId() != null && u.getRoleId() == 1L)) continue;
            Map<String, Object> m = new HashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("name", u.getNickname() == null || u.getNickname().isEmpty() ? u.getUsername() : u.getNickname());
            out.add(m);
        }
        return R.ok(out);
    }

    @PostMapping("/api/shares")
    public R<Map<String, Object>> create(@RequestBody ShareReq req) {
        boolean internal = "internal".equals(req.getShareType());
        permService.requireFile(currentUser.id(), req.getFileId(), internal ? "share_internal" : "share_external");
        Share s = shareService.create(currentUser.id(), req.getFileId(), req.getPermission(),
                req.getPassword(), req.getExpireDays(), req.getMaxDownloads(), req.getShareType(), req.getTargetIds());
        audit(internal ? "创建内部分享" : "创建外部分享", auditService.fileLabel(req.getFileId()), shareDetail(s));
        Map<String, Object> m = new HashMap<>();
        m.put("token", s.getToken());
        m.put("url", "/s/" + s.getToken());
        return R.ok(m, internal ? "内部分享已创建，仅指定用户可访问" : "共享链接已创建");
    }

    @PutMapping("/api/shares/{id}")
    public R<Void> update(@PathVariable Long id, @RequestBody UpdateReq req) {
        shareService.update(currentUser.id(), id, req.getPermission(), req.getPassword(), req.getExpireDays(),
                req.getMaxDownloads(), req.getShareType(), req.getTargetIds());
        Share after = shareMapper.selectById(id);
        audit("修改共享", auditService.fileLabel(after == null ? null : after.getFileId()),
                after == null ? "共享设置已更新" : "当前设置：" + shareDetail(after));
        return R.ok(null, "已更新");
    }

    @DeleteMapping("/api/shares/{id}")
    public R<Void> revoke(@PathVariable Long id) {
        Share before = shareMapper.selectById(id);
        shareService.revoke(currentUser.id(), id);
        audit("撤销共享", auditService.fileLabel(before == null ? null : before.getFileId()), "链接已失效，无法再访问");
        return R.ok(null, "已撤销");
    }

    @GetMapping("/api/shares/{id}/access")
    public R<List<ShareAccessLog>> access(@PathVariable Long id) {
        Long me = currentUser.id();
        Share s = shareMapper.selectById(id);
        if (s == null) throw new BizException(404, "共享不存在");
        FileMeta f = s.getFileId() == null ? null : fileMapper.selectById(s.getFileId());
        boolean isFileOwner = f != null && java.util.Objects.equals(f.getOwnerId(), me);
        if (!java.util.Objects.equals(s.getOwnerId(), me) && !isFileOwner && !permService.isAdmin(me)) {
            throw new BizException(403, "无权查看共享访问记录");
        }
        return R.ok(shareService.accessList(id));
    }

    // ===== 公开：访客免登录访问 =====

    @GetMapping("/api/share/{token}/info")
    public R<Map<String, Object>> info(@PathVariable String token,
                                       @RequestParam(required = false) String password,
                                       HttpServletRequest req) {
        String check = shareService.check(token, password, req);
        if ("NEED_PASSWORD".equals(check)) return R.ok(Collections.singletonMap("needPassword", true));
        if (check != null) throw new BizException(404, check);
        Share s = shareService.byToken(token);
        shareService.requireInternalAccess(s, currentUser.optionalId());
        FileMeta f = fileMapper.selectById(s.getFileId());
        if (f == null) throw new BizException(404, "共享文件已不存在");
        shareService.access(s.getId(), token, req);
        Map<String, Object> info = new HashMap<>();
        info.put("needPassword", false);
        info.put("permission", s.getPermission());
        info.put("expiresAt", s.getExpiresAt());
        info.put("expires_at", s.getExpiresAt());
        info.put("maxDownloads", s.getMaxDownloads());
        info.put("downloadCount", s.getDownloadCount());
        info.put("shareType", s.getShareType() == null ? "external" : s.getShareType());
        Map<String, Object> fm = new HashMap<>();
        fm.put("id", f.getId());
        fm.put("name", f.getName());
        fm.put("isDir", f.getIsDir());
        fm.put("is_dir", f.getIsDir());
        fm.put("ext", f.getExt());
        fm.put("size", f.getSize());
        fm.put("ownerId", f.getOwnerId());
        info.put("file", fm);
        return R.ok(info);
    }

    @GetMapping("/api/share/{token}/list")
    public R<List<FileMeta>> publicList(@PathVariable String token,
                                        @RequestParam(required = false) String password,
                                        @RequestParam(defaultValue = "0") Long parentId,
                                        HttpServletRequest req) {
        String check = shareService.check(token, password, req);
        if (check != null) throw new BizException(check.equals("NEED_PASSWORD") ? 403 : 404,
                check.equals("NEED_PASSWORD") ? "需要访问密码" : check);
        Share s = shareService.byToken(token);
        shareService.requireInternalAccess(s, currentUser.optionalId());
        FileMeta root = fileMapper.selectById(s.getFileId());
        if (root == null || root.getIsDir() != 1) return R.ok(Collections.emptyList());
        long pid = parentId == 0 ? root.getId() : parentId;
        if (pid != root.getId() && !shareService.insideShare(pid, root.getId())) {
            throw new BizException(403, "越权访问");
        }
        return R.ok(fileMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<FileMeta>()
                .eq(FileMeta::getParentId, pid)
                .eq(FileMeta::getOwnerId, root.getOwnerId())
                .eq(FileMeta::getIsDeleted, 0)
                .orderByDesc(FileMeta::getIsDir).orderByAsc(FileMeta::getName)));
    }

    @GetMapping("/api/share/{token}/download/{fileId}")
    public ResponseEntity<Resource> publicDownload(@PathVariable String token,
                                                   @PathVariable Long fileId,
                                                   @RequestParam(required = false) String password,
                                                   HttpServletRequest req) {
        String check = shareService.check(token, password, req);
        if (check != null) throw new BizException(403, check.equals("NEED_PASSWORD") ? "需要访问密码" : check);
        Share s = shareService.byToken(token);
        shareService.requireInternalAccess(s, currentUser.optionalId());
        if (!"download".equals(s.getPermission()) && !"edit".equals(s.getPermission())) {
            throw new BizException(403, "该共享不允许下载");
        }
        FileMeta root = fileMapper.selectById(s.getFileId());
        FileMeta f = fileMapper.selectById(fileId);
        if (root == null || f == null || f.getIsDir() == 1 || !f.getOwnerId().equals(root.getOwnerId())) {
            throw new BizException(404, "文件不存在");
        }
        if (!fileId.equals(s.getFileId()) && !shareService.insideShare(fileId, s.getFileId())) {
            throw new BizException(403, "越权访问");
        }
        Path path = fileUtil.storagePath(f.getOwnerId(), f.getStorageKey());
        if (!Files.exists(path)) throw new BizException(404, "文件实体丢失");
        shareService.countDownload(s.getId());
        audit("共享下载", "文件：" + f.getName(), "通过对外共享链接下载");
        return FileResponses.build(path, f.getName(), false, req);
    }

    @GetMapping("/api/share/{token}/preview/{fileId}")
    public ResponseEntity<Resource> publicPreview(@PathVariable String token,
                                                  @PathVariable Long fileId,
                                                  @RequestParam(required = false) String password,
                                                  HttpServletRequest req) {
        String check = shareService.check(token, password, req);
        if (check != null) throw new BizException(403, check.equals("NEED_PASSWORD") ? "需要访问密码" : check);
        Share s = shareService.byToken(token);
        shareService.requireInternalAccess(s, currentUser.optionalId());
        FileMeta root = fileMapper.selectById(s.getFileId());
        FileMeta f = fileMapper.selectById(fileId);
        if (root == null || f == null || f.getIsDir() == 1 || !f.getOwnerId().equals(root.getOwnerId())) {
            throw new BizException(404, "文件不存在");
        }
        if (!fileId.equals(s.getFileId()) && !shareService.insideShare(fileId, s.getFileId())) {
            throw new BizException(403, "越权访问");
        }
        Path path = fileUtil.storagePath(f.getOwnerId(), f.getStorageKey());
        if (!Files.exists(path)) throw new BizException(404, "文件实体丢失");
        return FileResponses.build(path, f.getName(), true, req);
    }

    /** 共享页 Office 只读预览（移动端同样禁用编辑） */
    @GetMapping("/api/share/{token}/office-preview/{fileId}")
    public ResponseEntity<Resource> publicOfficePreview(@PathVariable String token,
                                                        @PathVariable Long fileId,
                                                        @RequestParam(required = false) String password,
                                                        HttpServletRequest req) {
        String check = shareService.check(token, password, req);
        if (check != null) throw new BizException(403, check.equals("NEED_PASSWORD") ? "需要访问密码" : check);
        Share s = shareService.byToken(token);
        shareService.requireInternalAccess(s, currentUser.optionalId());
        FileMeta root = fileMapper.selectById(s.getFileId());
        FileMeta f = fileMapper.selectById(fileId);
        if (root == null || f == null || f.getIsDir() == 1 || !f.getOwnerId().equals(root.getOwnerId())) {
            throw new BizException(404, "文件不存在");
        }
        if (!fileId.equals(s.getFileId()) && !shareService.insideShare(fileId, s.getFileId())) {
            throw new BizException(403, "越权访问");
        }
        Path path = fileUtil.storagePath(f.getOwnerId(), f.getStorageKey());
        if (!Files.exists(path)) throw new BizException(404, "文件实体丢失");
        String key = f.getMd5() == null || f.getMd5().isEmpty() ? f.getStorageKey() : f.getMd5();
        if (!officeService.enabled()) throw new BizException(503, "Office 在线预览已在系统配置中关闭，请下载后查看");
        String ext = f.getExt() == null || f.getExt().isEmpty() ? FileUtil.ext(f.getName()) : f.getExt().toLowerCase();
        boolean libre = officeService.binary() != null;
        // 与主站一致：xlsx 固定走带工作表页签的 HTML，转 PDF 会丢掉页签与其他工作表
        if (OfficeHtmlService.supported(ext) && ("xlsx".equals(ext) || !libre)) {
            try {
                Path html = officeHtmlService.render(path, f.getName(), ext, key);
                return FileResponses.build(html, "preview.html", true, req);
            } catch (BizException e) {
                if (!libre) throw e;
            }
        }
        if (libre) {
            Path pdf = officeService.convert(path, key);
            return FileResponses.build(pdf, "preview.pdf", true, req);
        }
        throw new BizException(503, "服务器未安装 LibreOffice，旧版 ." + ext + " 暂不支持在线预览，请下载后查看");
    }

    /** 共享设置的白话描述，供审计日志的详情列使用 */
    private String shareDetail(Share s) {
        if (s == null) return "共享设置已更新";
        List<String> parts = new ArrayList<>();
        parts.add("internal".equals(s.getShareType()) ? "内部分享" : "外部分享");
        String perm = s.getPermission() == null ? "view" : s.getPermission().toLowerCase();
        parts.add("edit".equals(perm) || "download".equals(perm) ? "可查看、可下载" : "仅可查看");
        String exp = s.getExpiresAt();
        if (exp == null || exp.trim().isEmpty()) {
            parts.add("长期有效");
        } else {
            parts.add("有效期至 " + (exp.length() > 16 ? exp.substring(0, 16) : exp));
        }
        if (s.getPassword() != null && !s.getPassword().isEmpty()) parts.add("需密码访问");
        if (s.getMaxDownloads() != null && s.getMaxDownloads() > 0) {
            parts.add("限 " + s.getMaxDownloads() + " 次下载");
        } else {
            parts.add("下载次数不限");
        }
        return String.join(" · ", parts);
    }

}
