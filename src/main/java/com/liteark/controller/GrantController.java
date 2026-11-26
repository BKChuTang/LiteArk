package com.liteark.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liteark.common.BizException;
import com.liteark.common.R;
import com.liteark.entity.FileMeta;
import com.liteark.entity.Permission;
import com.liteark.entity.Role;
import com.liteark.entity.User;
import com.liteark.mapper.FileMetaMapper;
import com.liteark.mapper.PermissionMapper;
import com.liteark.mapper.RoleMapper;
import com.liteark.mapper.UserMapper;
import com.liteark.service.PermissionService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * 普通用户侧的文件夹授权入口（自助共享给同事）。
 *
 * 与管理端 /admin/permissions 的差异：
 * 1. 只能对**自己拥有**的文件夹授权，不能碰他人目录（管理员不受此限）；
 * 2. 能授出的权限不超过自己在目标文件夹上的有效权限，避免越权放大；
 * 3. 授权对象只能是普通用户（管理员本身已有全部权限，无需授权）；
 * 4. 规则模型与管理端完全一致——挂在文件夹上、递归覆盖全部下级、按目录链实时取交集。
 *
 * 角色默认权限（V1.2）：可在授权时选择“按角色授权”，系统读取该角色在该文件夹上的
 * 默认权限并作为实际授权范围（同样受本人在该目录的可授出上限约束），规则记录来源角色，
 * 角色默认权限调整后该同事的权限自动跟随。
 */
@RestController
@RequestMapping("/api/grants")
@RequiredArgsConstructor
public class GrantController extends BaseController {

    private final PermissionService permService;
    private final PermissionMapper permissionMapper;
    private final FileMetaMapper fileMapper;
    private final UserMapper userMapper;
    private final RoleMapper roleMapper;

    @Data
    public static class GrantReq {
        private Long folderId;
        private Long userId;
        private List<String> perms;
        private Integer inherit;
        /** 非空表示按角色授权：读取该角色的默认文件夹权限，perms 作为授权上限 */
        private Long roleId;
    }

    /**
     * 授权面板数据：该文件夹上（含下级）已有的规则、可授出的权限上限、可选用户。
     * 前端据此渲染「授权」对话框；无权限时返回 403，按钮也会提前隐藏。
     */
    @GetMapping
    public R<Map<String, Object>> detail(@RequestParam Long folderId) {
        Long me = currentUser.id();
        FileMeta folder = guard(me, folderId);

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("folderId", folder.getId());
        m.put("folderName", folder.getName());
        m.put("path", pathOf(folder));
        m.put("ownerId", folder.getOwnerId());
        m.put("ownerName", label(folder.getOwnerId()));
        // 我能授出的最大权限集合（所有者通常是四位全开；管理员若对本人做了收窄则以收窄后为准）
        m.put("maxPerms", new ArrayList<>(permService.effective(me, folder.getId())));
        m.put("roles", roles(folder));
        m.put("rules", rulesOf(folder));
        m.put("candidates", candidates(me, folder));
        return R.ok(m);
    }

    /** 给某个普通用户授权（重复保存即覆盖），权限对该文件夹及全部下级递归生效 */
    @PostMapping
    public R<Void> grant(@RequestBody GrantReq req) {
        Long me = currentUser.id();
        if (req == null || req.getFolderId() == null) throw new BizException(400, "请指定要授权的文件夹");
        FileMeta folder = guard(me, req.getFolderId());
        if (req.getUserId() == null) throw new BizException(400, "请选择授权对象");
        User target = userMapper.selectById(req.getUserId());
        if (target == null) throw new BizException(404, "用户不存在");
        if (Objects.equals(target.getId(), folder.getOwnerId())) {
            throw new BizException(400, "文件夹所有者本身已拥有全部权限，无需授权");
        }
        if (Objects.equals(target.getRoleId(), 1L)) {
            throw new BizException(400, "管理员已拥有全部权限，无需单独授权");
        }

        // 可授出权限 = 请求权限 ∩ 我在该文件夹上的有效权限
        Set<String> ceiling = permService.effective(me, folder.getId());
        Set<String> clean = new LinkedHashSet<>();
        Set<String> storeCap = new LinkedHashSet<>();
        Long roleId = req.getRoleId();
        if (roleId != null) {
            Role role = roleMapper.selectById(roleId);
            if (role == null) throw new BizException(404, "角色不存在");
            if (Objects.equals(roleId, 1L)) throw new BizException(400, "管理员角色拥有全部权限，不可作为授权模板");
            // 按角色授权：勾选的是“上限”，实际权限 = 角色在该目录的默认权限 ∩ 上限 ∩ 我能授出的范围。
            // 上限单独存，角色默认权限调整后用户自动跟随，同时不会超过上限。
            Set<String> roleDefault = permService.roleEffective(roleId, folder.getId());
            storeCap = req.getPerms() == null ? new LinkedHashSet<>(ceiling) : intersect(cleanOf(req.getPerms()), ceiling);
            clean = intersect(roleDefault, storeCap);
        } else if (req.getPerms() != null) {
            for (String p : cleanOf(req.getPerms())) {
                if (ceiling.contains(p)) clean.add(p);
            }
            storeCap = clean;
        }
        permService.save("user", target.getId(), folder.getId(), storeCap, req.getInherit(), roleId);
        audit("授权文件夹", "用户：" + auditService.userName(target.getId()),
                "文件夹「" + pathOf(folder) + "」→ " + com.liteark.service.AuditService.perms(clean)
                        + (roleId == null
                        ? "（直接指定）"
                        : "（按角色「" + auditService.roleName(roleId) + "」自动跟随）"));
        if (clean.isEmpty()) {
            return R.ok(null, roleId == null ? "已保存：该用户对该文件夹不可见"
                    : "已保存：该角色在此文件夹没有默认权限（或超出你的可授出范围），该用户不可见");
        }
        return R.ok(null, roleId == null
                ? "已授权，对该文件夹及其全部下级（含后续新增）生效"
                : "已按角色授权，该同事的权限随角色默认配置自动调整，且不超过本次设定");
    }

    /** 我授权出去的规则（集中管理入口，供「我的分享」页展示） */
    @GetMapping("/mine")
    public R<List<Map<String, Object>>> mine() {
        Long me = currentUser.id();
        List<FileMeta> dirs = fileMapper.selectList(new LambdaQueryWrapper<FileMeta>()
                .eq(FileMeta::getOwnerId, me)
                .eq(FileMeta::getIsDir, 1)
                .eq(FileMeta::getIsDeleted, 0));
        Map<Long, FileMeta> byId = new HashMap<>();
        for (FileMeta f : dirs) byId.put(f.getId(), f);
        List<Map<String, Object>> out = new ArrayList<>();
        for (FileMeta f : dirs) {
            for (Permission p : permissionMapper.selectList(new LambdaQueryWrapper<Permission>()
                    .eq(Permission::getFolderId, f.getId()))) {
                if (!"user".equals(p.getSubjectType())) continue;   // 角色规则由管理员维护
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p.getId());
                m.put("folderId", f.getId());
                m.put("folderPath", pathOf(f));
                m.put("subjectName", label(p.getSubjectId()));
                m.put("subjectId", p.getSubjectId());
                Set<String> cap = cleanOf(p.getPermBits() == null || p.getPermBits().isEmpty()
                        ? Collections.emptyList() : Arrays.asList(p.getPermBits().split(",")));
                // 规则引用角色时，展示“实际生效权限”而不是上限，避免看起来被放大了
                Set<String> actual = p.getSourceRoleId() == null
                        ? cap : intersect(permService.roleEffective(p.getSourceRoleId(), p.getFolderId()), cap);
                m.put("perms", new ArrayList<>(actual));
                m.put("cap", new ArrayList<>(cap));
                m.put("inherit", p.getInherit());
                m.put("sourceRoleId", p.getSourceRoleId());
                m.put("sourceRoleName", p.getSourceRoleId() == null ? "" : roleName(p.getSourceRoleId()));
                out.add(m);
            }
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("folderPath"))));
        return R.ok(out);
    }

    /** 撤销某条授权规则 */
    @DeleteMapping("/{id}")
    public R<Void> revoke(@PathVariable Long id) {
        Permission p = permissionMapper.selectById(id);
        if (p == null) throw new BizException(404, "规则不存在");
        if (!"user".equals(p.getSubjectType())) throw new BizException(400, "角色默认权限由管理员在「角色管理」维护，不能在此撤销");
        FileMeta folder = guard(currentUser.id(), p.getFolderId());
        permissionMapper.deleteById(p.getId());
        audit("撤销文件夹授权", "用户：" + auditService.userName(p.getSubjectId()),
                "已收回文件夹「" + pathOf(folder) + "」的访问权限");
        return R.ok(null, "已撤销该用户的授权");
    }

    // ================= 内部 =================

    /** 只有文件夹所有者或管理员可以在此授权，且必须是真实存在的文件夹 */
    private FileMeta guard(Long me, Long folderId) {
        if (folderId == null || folderId <= 0) {
            throw new BizException(400, "根目录不支持授权，请先新建文件夹再授权");
        }
        FileMeta f = fileMapper.selectById(folderId);
        if (f == null || (f.getIsDeleted() != null && f.getIsDeleted() == 1)) {
            throw new BizException(404, "文件夹不存在");
        }
        if (f.getIsDir() == null || f.getIsDir() != 1) throw new BizException(400, "只能对文件夹授权");
        if (!Objects.equals(f.getOwnerId(), me) && !permService.isAdmin(me)) {
            throw new BizException(403, "只有文件夹所有者可以授权");
        }
        return f;
    }

    /** 该文件夹及其全部下级目录上的规则（含主体名称与相对路径） */
    private List<Map<String, Object>> rulesOf(FileMeta root) {
        Set<Long> scope = subtree(root);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Long id : scope) {
            for (Permission p : permissionMapper.selectList(new LambdaQueryWrapper<Permission>()
                    .eq(Permission::getFolderId, id))) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", p.getId());
                m.put("folderId", p.getFolderId());
                m.put("folderPath", relativePath(root.getId(), p.getFolderId()));
                m.put("subjectType", p.getSubjectType());
                m.put("subjectId", p.getSubjectId());
                m.put("subjectName", "role".equals(p.getSubjectType())
                        ? "角色 · " + roleName(p.getSubjectId()) : label(p.getSubjectId()));
                Set<String> cap = cleanOf(p.getPermBits() == null || p.getPermBits().isEmpty()
                        ? Collections.emptyList() : Arrays.asList(p.getPermBits().split(",")));
                Set<String> actual = p.getSourceRoleId() == null
                        ? cap : intersect(permService.roleEffective(p.getSourceRoleId(), p.getFolderId()), cap);
                m.put("perms", new ArrayList<>(actual));
                m.put("cap", new ArrayList<>(cap));
                m.put("inherit", p.getInherit());
                m.put("sourceRoleId", p.getSourceRoleId());
                m.put("sourceRoleName", p.getSourceRoleId() == null ? "" : roleName(p.getSourceRoleId()));
                // 只有直接挂在本文件夹上的规则可在此撤销，下级目录的规则提示去对应目录处理
                m.put("here", Objects.equals(p.getFolderId(), root.getId()));
                out.add(m);
            }
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("folderPath"))));
        return out;
    }

    /**
     * 可选的“角色模板”：普通角色的默认文件夹权限。
     * 同时给出该角色下用户数，便于授权时判断影响面；1 号管理员角色不作为模板。
     */
    private List<Map<String, Object>> roles(FileMeta folder) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Role r : roleMapper.selectList(new LambdaQueryWrapper<Role>().orderByAsc(Role::getId))) {
            if (Objects.equals(r.getId(), 1L)) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", r.getId());
            m.put("name", r.getName());
            m.put("description", r.getDescription());
            m.put("userCount", userMapper.selectCount(new LambdaQueryWrapper<User>().eq(User::getRoleId, r.getId())));
            m.put("perms", new ArrayList<>(permService.roleEffective(r.getId(), folder.getId())));
            out.add(m);
        }
        return out;
    }

    /** 角色名（用于规则来源展示） */
    private String roleName(Long roleId) {
        Role r = roleId == null ? null : roleMapper.selectById(roleId);
        return r == null ? "" : r.getName();
    }

    private Set<String> cleanOf(List<String> perms) {
        Set<String> out = new LinkedHashSet<>();
        if (perms == null) return out;
        for (String p : perms) {
            String n = PermissionService.normalize(p);
            if (PermissionService.PERMS.contains(n)) out.add(n);
        }
        return out;
    }

    private Set<String> intersect(Set<String> a, Set<String> b) {
        Set<String> out = new LinkedHashSet<>();
        for (String p : PermissionService.PERMS) {
            if (a.contains(p) && b.contains(p)) out.add(p);
        }
        return out;
    }

    /** 可选授权对象：除自己以外的普通用户，附带其在本文件夹上已有的规则，便于回填 */
    private List<Map<String, Object>> candidates(Long me, FileMeta folder) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (User u : userMapper.selectList(new LambdaQueryWrapper<User>()
                .eq(User::getStatus, 1)
                .orderByAsc(User::getUsername))) {
            if (Objects.equals(u.getId(), me) || Objects.equals(u.getId(), folder.getOwnerId())) continue;
            if (Objects.equals(u.getRoleId(), 1L)) continue;
            Permission own = permissionMapper.selectOne(new LambdaQueryWrapper<Permission>()
                    .eq(Permission::getSubjectType, "user")
                    .eq(Permission::getSubjectId, u.getId())
                    .eq(Permission::getFolderId, folder.getId())
                    .last("limit 1"));
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", u.getId());
            m.put("username", u.getUsername());
            m.put("name", label(u.getId()));
            m.put("perms", own == null || own.getPermBits() == null || own.getPermBits().isEmpty()
                    ? Collections.emptyList() : Arrays.asList(own.getPermBits().split(",")));
            m.put("inherit", own == null ? 1 : own.getInherit());
            m.put("roleId", u.getRoleId());
            m.put("roleName", roleName(u.getRoleId()));
            m.put("sourceRoleId", own == null ? null : own.getSourceRoleId());
            out.add(m);
        }
        return out;
    }

    /** 该文件夹及其全部下级目录 ID */
    private Set<Long> subtree(FileMeta root) {
        Set<Long> ids = new LinkedHashSet<>();
        ids.add(root.getId());
        List<FileMeta> all = fileMapper.selectList(new LambdaQueryWrapper<FileMeta>()
                .eq(FileMeta::getOwnerId, root.getOwnerId())
                .eq(FileMeta::getIsDir, 1)
                .eq(FileMeta::getIsDeleted, 0));
        boolean grew = true;
        while (grew) {                       // 逐层展开，避免递归建树
            grew = false;
            for (FileMeta f : all) {
                if (ids.contains(f.getId())) continue;
                if (f.getParentId() != null && ids.contains(f.getParentId())) {
                    ids.add(f.getId());
                    grew = true;
                }
            }
        }
        return ids;
    }

    /** 相对授权根的路径，根自身返回「当前文件夹」 */
    private String relativePath(Long rootId, Long folderId) {
        if (Objects.equals(rootId, folderId)) return "当前文件夹";
        LinkedList<String> names = new LinkedList<>();
        Long cur = folderId;
        int guard = 0;
        while (cur != null && cur > 0 && guard++ < 500) {
            if (Objects.equals(cur, rootId)) break;
            FileMeta f = fileMapper.selectById(cur);
            if (f == null) break;
            names.addFirst(f.getName());
            cur = f.getParentId();
        }
        return String.join(" / ", names);
    }

    private String pathOf(FileMeta folder) {
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

    private String label(Long userId) {
        if (userId == null) return "未知用户";
        User u = userMapper.selectById(userId);
        if (u == null) return "未知用户";
        return u.getNickname() == null || u.getNickname().isEmpty() ? u.getUsername() : u.getNickname();
    }
}
