package com.liteark.service;

import com.liteark.entity.FileMeta;
import com.liteark.entity.Permission;
import com.liteark.entity.Role;
import com.liteark.entity.Share;
import com.liteark.entity.User;
import com.liteark.mapper.FileMetaMapper;
import com.liteark.mapper.PermissionMapper;
import com.liteark.mapper.RoleMapper;
import com.liteark.mapper.ShareMapper;
import com.liteark.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Set;

/**
 * 审计日志的「业务语言」转换。
 *
 * <p>日志是给管理员看的，不该出现内部 ID、token、权限位英文。这里统一把
 * ID 翻成名称、权限位翻成中文，调用点只负责组织一句话的其余部分。
 */
@Service
@RequiredArgsConstructor
public class AuditService {

    private final FileMetaMapper fileMapper;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;
    private final ShareMapper shareMapper;
    private final PermissionMapper permissionMapper;

    /** 目录路径；id 为 0 或空表示根目录 */
    public String dirPath(Long folderId) {
        if (folderId == null || folderId <= 0) return "根目录";
        LinkedList<String> names = new LinkedList<>();
        Long cur = folderId;
        int guard = 0;
        while (cur != null && cur > 0 && guard++ < 200) {
            FileMeta f = fileMapper.selectById(cur);
            if (f == null) break;
            names.addFirst(f.getName());
            cur = f.getParentId();
        }
        if (names.isEmpty()) return "根目录";
        return String.join(" / ", names);
    }

    /** 文件或文件夹名称；已被彻底删除时给出可读说明 */
    public String fileName(Long fileId) {
        if (fileId == null) return "未知文件";
        FileMeta f = fileMapper.selectById(fileId);
        if (f == null) return "已删除的文件";
        return f.getName();
    }

    /** 文件或文件夹名称 + 类型，如「文件夹：项目文档」 */
    public String fileLabel(Long fileId) {
        if (fileId == null) return "未知文件";
        FileMeta f = fileMapper.selectById(fileId);
        if (f == null) return "已删除的文件";
        return (f.getIsDir() != null && f.getIsDir() == 1 ? "文件夹：" : "文件：") + f.getName();
    }

    /** 用户显示名，昵称优先，其次登录名 */
    public String userName(Long userId) {
        if (userId == null) return "未知用户";
        User u = userMapper.selectById(userId);
        if (u == null) return "未知用户";
        return u.getNickname() == null || u.getNickname().isEmpty() ? u.getUsername() : u.getNickname();
    }

    /** 登录名（用于账号类操作，避免昵称重名造成歧义） */
    public String loginName(Long userId) {
        User u = userId == null ? null : userMapper.selectById(userId);
        return u == null ? "未知用户" : u.getUsername();
    }

    public String roleName(Long roleId) {
        Role r = roleId == null ? null : roleMapper.selectById(roleId);
        return r == null ? "未知角色" : r.getName();
    }

    /** 共享链接指向的文件名 */
    public String shareTarget(Long shareId) {
        Share s = shareId == null ? null : shareMapper.selectById(shareId);
        if (s == null) return "已删除的共享";
        return fileName(s.getFileId());
    }

    /** 批量操作的资源描述：单条带类型前缀，多条给「首个 等 N 项」 */
    public String briefNames(java.util.List<Long> ids) {
        if (ids == null || ids.isEmpty()) return "未选择项目";
        if (ids.size() == 1) return fileLabel(ids.get(0));
        java.util.List<String> names = new java.util.ArrayList<>();
        for (Long id : ids) {
            FileMeta f = id == null ? null : fileMapper.selectById(id);
            if (f != null && f.getName() != null && !f.getName().isEmpty()) names.add(f.getName());
            if (names.size() >= 3) break;
        }
        if (names.isEmpty()) return ids.size() + " 项";
        return names.get(0) + " 等 " + ids.size() + " 项";
    }

    /** 已知名称的批量描述，用于上传这类名称直接可得的场景 */
    public static String briefList(java.util.List<String> names) {
        if (names == null || names.isEmpty()) return "0 个文件";
        if (names.size() == 1) return "文件：" + names.get(0);
        return names.get(0) + " 等 " + names.size() + " 个文件";
    }

    /** 权限位转中文，如「查看、可下载」。空集合表示「不可见」 */
    public static String perms(Collection<String> perms) {
        if (perms == null || perms.isEmpty()) return "不可见";
        Set<String> clean = new LinkedHashSet<>();
        for (String p : perms) {
            if (p == null || p.trim().isEmpty()) continue;
            clean.add(PermissionService.label(p));
        }
        return clean.isEmpty() ? "不可见" : String.join("、", clean);
    }

    /** 逗号分隔的权限位字符串转中文 */
    public static String perms(String permBits) {
        if (permBits == null || permBits.trim().isEmpty()) return "不可见";
        Set<String> out = new LinkedHashSet<>();
        for (String p : permBits.split(",")) {
            if (p == null || p.trim().isEmpty()) continue;
            out.add(p.trim());
        }
        return perms(out);
    }

    /** 端类型中文化 */
    public static String client(String clientType) {
        if (clientType == null) return "电脑端";
        return "Mobile".equalsIgnoreCase(clientType) ? "手机端" : "电脑端";
    }

    /** 系统配置项的中文名，未收录的原样返回 */
    private static final java.util.Map<String, String> CONFIG_LABELS = new java.util.LinkedHashMap<>();
    static {
        CONFIG_LABELS.put("site_name", "站点名称");
        CONFIG_LABELS.put("default_quota", "默认用户配额");
        CONFIG_LABELS.put("max_file_size", "单文件上限");
        CONFIG_LABELS.put("chunk_size_pc", "PC 分片大小");
        CONFIG_LABELS.put("chunk_size_mobile", "移动端分片大小");
        CONFIG_LABELS.put("conflict_strategy", "同名冲突策略");
        CONFIG_LABELS.put("upload_concurrency_pc", "PC 上传并发");
        CONFIG_LABELS.put("upload_concurrency_mobile", "移动端上传并发");
        CONFIG_LABELS.put("default_share_expire", "共享默认有效期");
        CONFIG_LABELS.put("recycle_retention_days", "回收站保留天数");
        CONFIG_LABELS.put("log_retention_days", "日志保留天数");
        CONFIG_LABELS.put("online_edit_enabled", "在线编辑开关");
        CONFIG_LABELS.put("edit_ext_whitelist", "编辑后缀白名单");
        CONFIG_LABELS.put("office_preview_enabled", "Office 转码预览开关");
        CONFIG_LABELS.put("totp_global_enabled", "二次验证总开关");
        CONFIG_LABELS.put("totp_max_attempts", "验证码错误阈值");
        CONFIG_LABELS.put("totp_lock_minutes", "验证锁定时长");
        CONFIG_LABELS.put("totp_window", "验证码时间容错窗口");
        CONFIG_LABELS.put("totp_recovery_count", "恢复码生成数量");
        CONFIG_LABELS.put("totp_disable_policy", "禁用二次验证处置策略");
        CONFIG_LABELS.put("trusted_device_ttl_days", "信任设备有效时长");
    }

    public static String configLabel(String key) {
        if (key == null) return "";
        return CONFIG_LABELS.getOrDefault(key, key);
    }

    /** 「站点名称、日志保留天数」这样的配置项串 */
    public static String configLabels(Collection<String> keys) {
        if (keys == null || keys.isEmpty()) return "";
        Set<String> out = new LinkedHashSet<>();
        for (String k : keys) {
            if (k != null && !k.trim().isEmpty()) out.add(configLabel(k.trim()));
        }
        return String.join("、", out);
    }

    /** 某条文件夹权限规则的授权对象描述：用户：张三 / 角色：普通用户 */
    public String permissionSubject(Long ruleId) {
        Permission p = ruleId == null ? null : permissionMapper.selectById(ruleId);
        if (p == null) return "未知授权";
        if ("role".equals(p.getSubjectType())) return "角色：" + roleName(p.getSubjectId());
        return "用户：" + userName(p.getSubjectId());
    }

    /** 某条文件夹权限规则指向的文件夹路径 */
    public String permissionFolder(Long ruleId) {
        Permission p = ruleId == null ? null : permissionMapper.selectById(ruleId);
        return p == null ? "未知文件夹" : dirPath(p.getFolderId());
    }
}
