package com.liteark.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.liteark.common.BizException;
import com.liteark.common.R;
import com.liteark.config.LiteArkProperties;
import com.liteark.entity.*;
import com.liteark.mapper.*;
import com.liteark.security.CurrentUser;
import com.liteark.service.PermissionService;
import com.liteark.service.TotpService;
import com.liteark.util.FileUtil;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController extends BaseController {
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final FileMetaMapper fileMapper;
    private final ShareMapper shareMapper;
    private final OperationLogMapper logMapper;
    private final SystemConfigMapper configMapper;
    private final com.liteark.mapper.ShareAccessLogMapper accessMapper;
    private final BCryptPasswordEncoder encoder;
    private final CurrentUser current;
    private final LiteArkProperties props;
    private final FileUtil fileUtil;
    private final PermissionService permService;
    private final TotpService totpService;

    private void admin() { current.requireAdmin(); }

    @GetMapping("/overview")
    public R<Map<String, Object>> overview() {
        admin();
        Map<String, Object> m = new HashMap<>();
        m.put("users", userMapper.selectCount(null));
        m.put("files", fileMapper.selectCount(new LambdaQueryWrapper<FileMeta>().eq(FileMeta::getIsDeleted, 0)));
        Long size = fileMapper.selectList(new LambdaQueryWrapper<FileMeta>()
                .eq(FileMeta::getIsDeleted, 0).eq(FileMeta::getIsDir, 0))
                .stream().mapToLong(f -> f.getSize() == null ? 0 : f.getSize()).sum();
        m.put("totalSize", size);
        m.put("shares", shareMapper.selectCount(new LambdaQueryWrapper<Share>().eq(Share::getRevoked, 0)));
        m.put("logs", logMapper.selectCount(null));
        return R.ok(m);
    }

    // ===== 用户管理 =====
    @Data
    public static class UserReq {
        private String username;
        private String password;
        private String nickname;
        private Long roleId;
        private Long quota;
        private Integer status;
        private Integer totpAllowed;
    }

    @GetMapping("/users")
    public R<List<Map<String, Object>>> users() {
        admin();
        List<Map<String, Object>> list = new ArrayList<>();
        for (User u : userMapper.selectList(new LambdaQueryWrapper<User>().orderByAsc(User::getId))) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("nickname", u.getNickname());
            m.put("role_id", u.getRoleId());
            Role r = roleMapper.selectById(u.getRoleId());
            m.put("role_name", r == null ? "" : r.getName());
            m.put("quota", u.getQuota());
            m.put("used", u.getUsed());
            m.put("status", u.getStatus());
            m.put("created_at", u.getCreatedAt());
            m.put("last_login", u.getLastLogin());
            m.put("totp_allowed", totpService.allowed(u) ? 1 : 0);
            m.put("totp_enabled", totpService.isEnabled(u.getId()) ? 1 : 0);
            list.add(m);
        }
        return R.ok(list);
    }

    @PostMapping("/users")
    public R<Void> addUser(@RequestBody UserReq req) {
        admin();
        if (req.getUsername() == null || req.getPassword() == null) throw new BizException("账号和密码不能为空");
        if (req.getPassword().length() < 6) throw new BizException("密码至少 6 位");
        if (userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getUsername, req.getUsername())) > 0) {
            throw new BizException("账号已存在");
        }
        User u = new User();
        u.setUsername(req.getUsername());
        u.setPassword(encoder.encode(req.getPassword()));
        u.setNickname(req.getNickname() == null ? req.getUsername() : req.getNickname());
        u.setRoleId(req.getRoleId() == null ? 2L : req.getRoleId());
        u.setQuota(req.getQuota() == null || req.getQuota() <= 0 ? props.getDefaultQuota() : req.getQuota());
        u.setUsed(0L);
        u.setStatus(req.getStatus() == null ? 1 : req.getStatus());
        u.setTotpAllowed(req.getTotpAllowed() == null ? 1 : (req.getTotpAllowed() == 1 ? 1 : 0));
        userMapper.insert(u);
        audit("新增用户", "账号：" + req.getUsername(),
                "角色：" + auditService.roleName(u.getRoleId()));
        return R.ok(null, "用户创建成功");
    }

    @PutMapping("/users/{id}")
    public R<Void> updateUser(@PathVariable Long id, @RequestBody UserReq req) {
        admin();
        User u = userMapper.selectById(id);
        if (u == null) throw new BizException(404, "用户不存在");
        if (req.getNickname() != null) u.setNickname(req.getNickname());
        if (req.getRoleId() != null) u.setRoleId(req.getRoleId());
        if (req.getQuota() != null) u.setQuota(req.getQuota());
        if (req.getStatus() != null) u.setStatus(req.getStatus());
        userMapper.updateById(u);
        audit("修改用户", "账号：" + u.getUsername(), "角色与配额已更新");
        return R.ok(null, "保存成功");
    }

    @PutMapping("/users/{id}/password")
    public R<Void> resetPassword(@PathVariable Long id, @RequestBody Map<String, String> body) {
        admin();
        String pwd = body.get("password");
        if (pwd == null || pwd.length() < 6) throw new BizException("密码至少 6 位");
        User u = userMapper.selectById(id);
        if (u == null) throw new BizException(404, "用户不存在");
        u.setPassword(encoder.encode(pwd));
        userMapper.updateById(u);
        audit("重置密码", "账号：" + u.getUsername(), "登录密码已重置");
        return R.ok(null, "密码已重置");
    }

    @DeleteMapping("/users/{id}")
    public R<Void> deleteUser(@PathVariable Long id) {
        admin();
        User u = userMapper.selectById(id);
        if (u == null) throw new BizException(404, "用户不存在");
        if (id.equals(current.id())) throw new BizException("不能删除当前登录账号");
        // 删除物理文件与元数据
        List<FileMeta> files = fileMapper.selectList(new LambdaQueryWrapper<FileMeta>().eq(FileMeta::getOwnerId, id));
        for (FileMeta f : files) {
            if (f.getIsDir() == 0 && f.getStorageKey() != null) {
                try { Files.deleteIfExists(fileUtil.storagePath(id, f.getStorageKey())); } catch (Exception ignored) {}
            }
        }
        fileMapper.delete(new LambdaQueryWrapper<FileMeta>().eq(FileMeta::getOwnerId, id));
        shareMapper.delete(new LambdaQueryWrapper<Share>().eq(Share::getOwnerId, id));
        totpService.reset(id);
        userMapper.deleteById(id);
        audit("删除用户", "账号：" + u.getUsername(), "该账号及其名下文件已一并清除");
        return R.ok(null, "用户已删除");
    }

    // ===== 二次验证管理 =====
    @Data
    public static class Verification {
        private String type;
        private String code;
    }

    @Data
    public static class TotpAllowedReq {
        private Boolean allowed;
        private Verification verification;
    }

    @Data
    public static class TotpBatchReq {
        private List<Long> ids;
        private Boolean allowed;
        private Verification verification;
    }

    @Data
    public static class TotpResetReq {
        private Verification verification;
    }

    @PutMapping("/users/{id}/totp/allowed")
    public R<Void> setTotpAllowed(@PathVariable Long id, @RequestBody TotpAllowedReq req) {
        admin();
        User u = userMapper.selectById(id);
        if (u == null) throw new BizException(404, "用户不存在");
        if (req.getAllowed() == null) throw new BizException("请选择开通或禁止");
        verifySelf(req.getVerification(), false);
        int val = Boolean.TRUE.equals(req.getAllowed()) ? 1 : 0;
        u.setTotpAllowed(val);
        userMapper.updateById(u);
        boolean unbound = false;
        if (val == 0 && "B".equals(totpService.disablePolicy())) {
            unbound = totpService.reset(id);
        }
        audit("设置二次验证权限", "账号：" + u.getUsername(),
                (val == 1 ? "允许该账号自主开启二次验证" : "禁止该账号开启二次验证")
                        + (unbound ? "，已按策略 B 解除原有绑定" : "，原有绑定按策略 A 保留"));
        return R.ok(null, "保存成功");
    }

    @PostMapping("/users/totp/allowed/batch")
    public R<Map<String, Object>> batchTotpAllowed(@RequestBody TotpBatchReq req) {
        admin();
        if (req.getIds() == null || req.getIds().isEmpty()) throw new BizException("请至少选择一名用户");
        if (req.getAllowed() == null) throw new BizException("请选择开通或禁止");
        verifySelf(req.getVerification(), false);
        int val = Boolean.TRUE.equals(req.getAllowed()) ? 1 : 0;
        Set<Long> ids = new LinkedHashSet<>(req.getIds());
        int unbound = 0;
        for (Long id : ids) {
            User u = userMapper.selectById(id);
            if (u == null) continue;
            u.setTotpAllowed(val);
            userMapper.updateById(u);
            if (val == 0 && "B".equals(totpService.disablePolicy()) && totpService.reset(id)) unbound++;
        }
        audit("批量设置二次验证权限", "用户：" + ids.size() + " 名",
                (val == 1 ? "批量允许开启二次验证" : "批量禁止开启二次验证")
                        + (unbound > 0 ? "，已按策略 B 解除 " + unbound + " 个账号的绑定" : ""));
        Map<String, Object> m = new HashMap<>();
        m.put("count", ids.size());
        m.put("unbound", unbound);
        return R.ok(m);
    }

    @PostMapping("/users/{id}/totp/reset")
    public R<Void> resetTotp(@PathVariable Long id, @RequestBody TotpResetReq req) {
        admin();
        User u = userMapper.selectById(id);
        if (u == null) throw new BizException(404, "用户不存在");
        verifySelf(req.getVerification(), true);
        boolean had = totpService.reset(id);
        audit("重置二次验证", "账号：" + u.getUsername(),
                had ? "已清除密钥、恢复码与全部信任设备，该账号登录不再要求二次验证"
                        : "已清理该账号的二次验证残留数据");
        return R.ok(null, "已重置二次验证");
    }

    /** 管理员本人已开启二次验证时，敏感操作必须先完成动态验证码核验。 */
    private void verifySelf(Verification v, boolean allowRecovery) {
        User me = current.get();
        if (!totpService.isEnabled(me.getId())) return;
        if (v == null || v.getCode() == null || v.getCode().trim().isEmpty()) {
            throw new BizException("请先核验管理员本人的动态验证码");
        }
        try {
            String type = v.getType() == null ? "totp" : v.getType().toLowerCase();
            if ("recovery".equals(type)) {
                if (!allowRecovery) throw new BizException("此操作需要输入动态验证码");
                totpService.consumeRecoveryCode(me, v.getCode());
            } else if ("totp".equals(type)) {
                totpService.verifyCode(me, v.getCode());
            } else {
                throw new BizException("核验方式不支持");
            }
            auditFor(me, "管理员身份核验", "账号：" + me.getUsername(),
                    "通过" + ("recovery".equals(type) ? "备用恢复码" : "动态验证码") + "完成敏感操作核验");
        } catch (BizException e) {
            auditFor(me, "管理员身份核验", "账号：" + me.getUsername(), "身份核验未通过", "fail");
            throw e;
        }
    }

    // ===== 角色管理 =====
    @GetMapping("/roles")
    public R<List<Map<String, Object>>> roles() {
        admin();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Role r : roleMapper.selectList(new LambdaQueryWrapper<Role>().orderByAsc(Role::getId))) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", r.getId());
            m.put("name", r.getName());
            m.put("description", r.getDescription());
            m.put("is_system", r.getIsSystem());
            m.put("user_count", userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getRoleId, r.getId())));
            m.put("created_at", r.getCreatedAt());
            list.add(m);
        }
        return R.ok(list);
    }

    @PostMapping("/roles")
    public R<Void> addRole(@RequestBody Map<String, String> body) {
        admin();
        String name = body.get("name");
        if (name == null || name.trim().isEmpty()) throw new BizException("角色名称不能为空");
        if (roleMapper.selectCount(new LambdaQueryWrapper<Role>().eq(Role::getName, name)) > 0) {
            throw new BizException("角色名称已存在");
        }
        Role r = new Role();
        r.setName(name);
        r.setDescription(body.get("description") == null ? "" : body.get("description"));
        r.setIsSystem(0);
        roleMapper.insert(r);
        audit("新增角色", "角色：" + name, "可继续为其设置默认文件夹权限");
        return R.ok(null, "角色创建成功");
    }

    @PutMapping("/roles/{id}")
    public R<Void> updateRole(@PathVariable Long id, @RequestBody Map<String, String> body) {
        admin();
        Role r = roleMapper.selectById(id);
        if (r == null) throw new BizException(404, "角色不存在");
        if (r.getIsSystem() == 1) throw new BizException("系统内置角色不可修改");
        if (body.get("name") != null) r.setName(body.get("name"));
        if (body.get("description") != null) r.setDescription(body.get("description"));
        roleMapper.updateById(r);
        return R.ok(null, "保存成功");
    }

    @DeleteMapping("/roles/{id}")
    public R<Void> deleteRole(@PathVariable Long id) {
        admin();
        Role r = roleMapper.selectById(id);
        if (r == null) throw new BizException(404, "角色不存在");
        if (r.getIsSystem() == 1) throw new BizException("系统内置角色不可删除");
        long count = userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getRoleId, id));
        if (count > 0) throw new BizException("该角色下还有 " + count + " 个用户，无法删除");
        permService.detachRole(id);
        roleMapper.deleteById(id);
        audit("删除角色", "角色：" + r.getName(), "该角色的默认文件夹权限已一并清理");
        return R.ok(null, "角色已删除");
    }

    // ===== 共享管理 =====
    @GetMapping("/shares")
    public R<List<Map<String, Object>>> shares() {
        admin();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Share s : shareMapper.selectList(new LambdaQueryWrapper<Share>().eq(Share::getRevoked, 0)
                .orderByDesc(Share::getCreatedAt))) {
            Map<String, Object> m = new HashMap<>();
            m.put("id", s.getId());
            m.put("token", s.getToken());
            m.put("fileId", s.getFileId());
            m.put("permission", s.getPermission());
            m.put("shareType", s.getShareType() == null ? "external" : s.getShareType());
            m.put("targetUserIds", s.getTargetUserIds() == null ? "" : s.getTargetUserIds());
            m.put("password", s.getPassword());
            m.put("expiresAt", s.getExpiresAt());
            m.put("createdAt", s.getCreatedAt());
            FileMeta f = fileMapper.selectById(s.getFileId());
            m.put("fileName", f == null ? "" : f.getName());
            User u = userMapper.selectById(s.getOwnerId());
            m.put("ownerName", u == null ? "" : u.getUsername());
            m.put("maxDownloads", s.getMaxDownloads());
            m.put("downloadCount", s.getDownloadCount());
            list.add(m);
        }
        return R.ok(list);
    }

    @PutMapping("/shares/{id}")
    public R<Void> updateShare(@PathVariable Long id, @RequestBody Map<String, Object> body) {
        admin();
        Share s = shareMapper.selectById(id);
        if (s == null) throw new BizException(404, "共享不存在");
        if (body.get("permission") != null) s.setPermission(body.get("permission").toString());
        if (body.get("password") != null) s.setPassword(body.get("password").toString());
        if (body.get("revoked") != null) s.setRevoked(Integer.valueOf(body.get("revoked").toString()));
        Object exp = body.get("expireDays") != null ? body.get("expireDays") : body.get("expire_days");
        if (exp != null) {
            int days = Integer.parseInt(exp.toString());
            s.setExpiresAt(days > 0 ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                    .format(new Date(System.currentTimeMillis() + days * 86400000L)) : null);
        }
        if (body.get("maxDownloads") != null) {
            s.setMaxDownloads(Math.max(0, Integer.parseInt(body.get("maxDownloads").toString())));
            s.setDownloadCount(0);
        }
        shareMapper.updateById(s);
        audit("管控共享", auditService.fileLabel(s.getFileId()), "管理员调整了共享链接的设置");
        return R.ok(null, "保存成功");
    }

    @GetMapping("/shares/{id}/access")
    public R<List<ShareAccessLog>> shareAccess(@PathVariable Long id) {
        admin();
        return R.ok(accessMapper.selectList(new LambdaQueryWrapper<ShareAccessLog>()
                .eq(ShareAccessLog::getShareId, id)
                .orderByDesc(ShareAccessLog::getAccessedAt).last("limit 500")));
    }

    // ===== 日志 =====
    @GetMapping("/logs")
    public R<Map<String, Object>> logs(@RequestParam(defaultValue = "1") Integer page,
                                       @RequestParam(defaultValue = "20") Integer pageSize,
                                       @RequestParam(required = false) String username,
                                       @RequestParam(required = false) String action,
                                       @RequestParam(required = false) String start,
                                       @RequestParam(required = false) String end) {
        admin();
        LambdaQueryWrapper<OperationLog> q = new LambdaQueryWrapper<OperationLog>();
        if (username != null && !username.isEmpty()) q.like(OperationLog::getUsername, username);
        if (action != null && !action.isEmpty()) q.like(OperationLog::getAction, action);
        if (start != null && !start.isEmpty()) q.ge(OperationLog::getCreatedAt, start);
        if (end != null && !end.isEmpty()) q.le(OperationLog::getCreatedAt, end + " 23:59:59");
        q.orderByDesc(OperationLog::getId);
        IPage<OperationLog> p = logMapper.selectPage(new Page<>(page, pageSize), q);
        Map<String, Object> m = new HashMap<>();
        m.put("total", p.getTotal());
        m.put("list", p.getRecords());
        m.put("page", page);
        m.put("pageSize", pageSize);
        return R.ok(m);
    }

    // ===== 系统配置 =====
    @GetMapping("/config")
    public R<Map<String, String>> config() {
        admin();
        Map<String, String> m = new HashMap<>();
        for (SystemConfig c : configMapper.selectList(null)) m.put(c.getConfigKey(), c.getConfigValue());
        return R.ok(m);
    }

    @PutMapping("/config")
    public R<Void> saveConfig(@RequestBody Map<String, String> body) {
        admin();
        for (Map.Entry<String, String> e : body.entrySet()) {
            SystemConfig c = configMapper.selectById(e.getKey());
            if (c == null) {
                c = new SystemConfig();
                c.setConfigKey(e.getKey());
                c.setConfigValue(e.getValue());
                configMapper.insert(c);
            } else {
                c.setConfigValue(e.getValue());
                configMapper.updateById(c);
            }
        }
        audit("修改系统配置", "系统参数",
                "调整 " + body.size() + " 项：" + com.liteark.service.AuditService.configLabels(body.keySet()));
        return R.ok(null, "配置已保存");
    }

    // ===== 日志导出（需求 §5.4 / §10 Q-13：CSV） =====
    @GetMapping("/logs/export")
    public org.springframework.http.ResponseEntity<byte[]> exportLogs(
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String start,
            @RequestParam(required = false) String end) throws Exception {
        admin();
        LambdaQueryWrapper<OperationLog> q = new LambdaQueryWrapper<>();
        if (username != null && !username.isEmpty()) q.like(OperationLog::getUsername, username);
        if (action != null && !action.isEmpty()) q.like(OperationLog::getAction, action);
        if (start != null && !start.isEmpty()) q.ge(OperationLog::getCreatedAt, start);
        if (end != null && !end.isEmpty()) q.le(OperationLog::getCreatedAt, end + " 23:59:59");
        q.orderByDesc(OperationLog::getId).last("limit 100000");
        java.util.List<OperationLog> rows = logMapper.selectList(q);
        StringBuilder sb = new StringBuilder("\uFEFFID,时间,操作人,操作类型,资源,详情,IP,端类型,UA,结果\n");
        for (OperationLog l : rows) {
            sb.append(csv(l.getId())).append(',').append(csv(l.getCreatedAt())).append(',')
                    .append(csv(l.getUsername())).append(',').append(csv(l.getAction())).append(',')
                    .append(csv(l.getResource())).append(',').append(csv(l.getDetail())).append(',')
                    .append(csv(com.liteark.util.ClientUtil.normalizeIp(l.getIp()))).append(',')
                    .append(csv(l.getClientType())).append(',')
                    .append(csv(l.getUa())).append(',').append(csv(l.getResult())).append('\n');
        }
        audit("导出操作日志", "操作日志", "导出 " + rows.size() + " 条记录");
        return org.springframework.http.ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=liteark-logs.csv")
                .contentType(org.springframework.http.MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String csv(Object v) {
        if (v == null) return "";
        String s = String.valueOf(v).replace("\r", " ").replace("\n", " ");
        if (s.matches("^[=+\\-@].*")) s = "'" + s;
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    // ===== 细粒度权限配置（需求 §5.2） =====
    @Data
    public static class PermReq {
        private String subjectType;
        private Long subjectId;
        private Long folderId;
        private List<String> perms;
        private Integer inherit;
        /** 用户规则引用角色时的来源角色（非空表示按角色默认权限授权，perms 作为上限） */
        private Long sourceRoleId;
    }

    @GetMapping("/permissions")
    public R<List<Map<String, Object>>> permissions(@RequestParam String subjectType, @RequestParam Long subjectId) {
        admin();
        List<Map<String, Object>> list = new ArrayList<>();
        for (Permission p : permService.rules(subjectType, subjectId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("subjectType", p.getSubjectType());
            m.put("subjectId", p.getSubjectId());
            m.put("folderId", p.getFolderId());
            m.put("permBits", p.getPermBits());
            m.put("perms", p.getPermBits() == null || p.getPermBits().isEmpty()
                    ? Collections.emptyList() : Arrays.asList(p.getPermBits().split(",")));
            m.put("inherit", p.getInherit());
            m.put("sourceRoleId", p.getSourceRoleId());
            Role src = p.getSourceRoleId() == null ? null : roleMapper.selectById(p.getSourceRoleId());
            m.put("sourceRoleName", src == null ? "" : src.getName());
            FileMeta folder = fileMapper.selectById(p.getFolderId());
            m.put("folderName", folder == null ? "（目录已删除）" : folder.getName());
            m.put("folderPath", folder == null ? "" : folderPath(folder));
            m.put("ownerId", folder == null ? null : folder.getOwnerId());
            m.put("ownerName", folder == null ? "" : usernameOf(folder.getOwnerId()));
            list.add(m);
        }
        return R.ok(list);
    }

    /**
     * 管理端：指定用户（文件夹所有者）的文件夹树，供授权时选择“已有文件夹”。
     * 只返回文件夹，不含文件；节点带 path 便于在规则列表里展示完整路径。
     */
    @GetMapping("/folders")
    public R<List<Map<String, Object>>> folders(@RequestParam Long ownerId) {
        admin();
        List<FileMeta> dirs = fileMapper.selectList(new LambdaQueryWrapper<FileMeta>()
                .eq(FileMeta::getOwnerId, ownerId)
                .eq(FileMeta::getIsDir, 1)
                .eq(FileMeta::getIsDeleted, 0)
                .orderByAsc(FileMeta::getName));
        Map<Long, List<FileMeta>> byParent = new HashMap<>();
        for (FileMeta f : dirs) byParent.computeIfAbsent(f.getParentId() == null ? 0L : f.getParentId(), k -> new ArrayList<>()).add(f);
        List<Map<String, Object>> root = new ArrayList<>();
        Map<String, Object> rootNode = new LinkedHashMap<>();
        rootNode.put("id", 0);
        rootNode.put("label", usernameOf(ownerId) + " 的根目录");
        rootNode.put("path", "");
        rootNode.put("children", folderNodes(0L, "", byParent));
        root.add(rootNode);
        return R.ok(root);
    }

    private List<Map<String, Object>> folderNodes(Long parentId, String prefix, Map<Long, List<FileMeta>> byParent) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (FileMeta f : byParent.getOrDefault(parentId, Collections.emptyList())) {
            String path = prefix.isEmpty() ? f.getName() : prefix + " / " + f.getName();
            Map<String, Object> n = new LinkedHashMap<>();
            n.put("id", f.getId());
            n.put("label", f.getName());
            n.put("path", path);
            n.put("children", folderNodes(f.getId(), path, byParent));
            nodes.add(n);
        }
        return nodes;
    }

    private String folderPath(FileMeta folder) {
        LinkedList<String> names = new LinkedList<>();
        Long cur = folder.getId();
        int guard = 0;
        while (cur != null && cur > 0 && guard++ < 500) {
            FileMeta f = fileMapper.selectById(cur);
            if (f == null) break;
            names.addFirst(f.getName());
            cur = f.getParentId();
        }
        return String.join(" / ", names);
    }

    private String usernameOf(Long userId) {
        if (userId == null) return "";
        User u = userMapper.selectById(userId);
        if (u == null) return "未知用户";
        return u.getNickname() == null || u.getNickname().isEmpty() ? u.getUsername() : u.getNickname();
    }

    @PostMapping("/permissions")
    public R<Void> savePermission(@RequestBody PermReq req) {
        admin();
        Long sourceRoleId = req.getSourceRoleId();
        if (sourceRoleId != null) {
            if (!"user".equals(req.getSubjectType())) throw new BizException(400, "只有用户规则可以关联角色");
            if (roleMapper.selectById(sourceRoleId) == null) throw new BizException(404, "来源角色不存在");
            if (Objects.equals(sourceRoleId, 1L)) throw new BizException(400, "管理员角色拥有全部权限，不可作为授权模板");
            User target = userMapper.selectById(req.getSubjectId());
            if (target == null) throw new BizException(404, "用户不存在");
            if (Objects.equals(target.getRoleId(), 1L)) throw new BizException(400, "管理员无需单独授权");
        }
        permService.save(req.getSubjectType(), req.getSubjectId(), req.getFolderId(), req.getPerms(),
                req.getInherit(), sourceRoleId);
        String subject = "role".equals(req.getSubjectType())
                ? "角色：" + auditService.roleName(req.getSubjectId())
                : "用户：" + auditService.userName(req.getSubjectId());
        audit("设置文件夹权限", subject,
                "文件夹「" + auditService.dirPath(req.getFolderId()) + "」→ "
                        + com.liteark.service.AuditService.perms(req.getPerms())
                        + (sourceRoleId == null
                        ? "（直接指定）"
                        : "（按角色「" + auditService.roleName(sourceRoleId) + "」自动跟随）"));
        return R.ok(null, sourceRoleId == null
                ? "权限已保存，对该文件夹及其全部下级（含后续新增）生效"
                : "已按角色授权，实际权限随角色默认配置自动调整，且不超过本次勾选的上限");
    }

    /**
     * 角色在某文件夹上的默认权限（用户“按角色授权”时用于回填与提示）。
     * 返回角色名、角色下的用户数、以及沿目录链计算出的默认权限。
     */
    @GetMapping("/permissions/role-effective")
    public R<Map<String, Object>> roleEffective(@RequestParam Long roleId,
                                                @RequestParam(defaultValue = "0") Long folderId) {
        admin();
        Role r = roleMapper.selectById(roleId);
        if (r == null) throw new BizException(404, "角色不存在");
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("roleId", roleId);
        m.put("roleName", r.getName());
        m.put("userCount", userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getRoleId, roleId)));
        m.put("folderId", folderId);
        m.put("perms", new ArrayList<>(permService.roleEffective(roleId, folderId)));
        return R.ok(m);
    }

    @DeleteMapping("/permissions/{id}")
    public R<Void> deletePermission(@PathVariable Long id) {
        admin();
        String ruleSubject = auditService.permissionSubject(id);
        String ruleFolder = auditService.permissionFolder(id);
        permService.delete(id);
        audit("删除目录权限", ruleSubject, "已收回文件夹「" + ruleFolder + "」的权限规则");
        return R.ok(null, "规则已删除");
    }

    @GetMapping("/permissions/effective")
    public R<List<String>> effectivePermissions(@RequestParam Long userId, @RequestParam(defaultValue = "0") Long folderId) {
        admin();
        return R.ok(new ArrayList<>(permService.effective(userId, folderId)));
    }
}
