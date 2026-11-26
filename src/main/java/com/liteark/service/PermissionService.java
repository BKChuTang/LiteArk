package com.liteark.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.liteark.common.BizException;
import com.liteark.entity.FileMeta;
import com.liteark.entity.Permission;
import com.liteark.entity.User;
import com.liteark.mapper.FileMetaMapper;
import com.liteark.mapper.PermissionMapper;
import com.liteark.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 文件夹细化权限（需求 §5.2：查看 / 编辑 / 下载 / 上传）。
 *
 * 规则模型：
 * 1. 规则挂在**文件夹**上，subject 为用户或角色；对该文件夹及其全部下级生效，
 *    包括后续新增的文件与文件夹（按目录链实时计算，不复制规则，天然递归）。
 * 2. 上级权限约束下级：目录链自上而下逐层求交集，下级只能在上级授权范围内收窄，
 *    不会因为子目录配置而放大权限。
 * 3. inherit=false（阻断继承）：该层规则成为新的权限边界，不再向上取上级规则。
 * 4. 文件权限取自其所在文件夹；文件夹权限取自自身。
 * 5. 资源所有者与超级管理员始终拥有全部权限；其余用户未被授权的目录一律不可见。
 *
 * 角色与用户的关系（V1.2 调整）：
 * 6. **角色是文件夹权限的默认载体**——角色的目录规则即“角色默认文件夹权限”，
 *    角色下所有用户默认继承（用户自己的规则优先）。
 * 7. 用户授权时可直接指定权限，也可“按角色授权”：用户规则记录来源角色（sourceRoleId），
 *    权限 = 角色在该目录的默认权限 ∩ 授权时设定的上限。角色默认权限调整后用户自动跟随，
 *    同时永远不会超过当初授予的上限。
 */
@Service
@RequiredArgsConstructor
public class PermissionService {

    /** 细化权限位（顺序即界面展示顺序）；后两位控制能否发起内/外部分享 */
    public static final List<String> PERMS = Collections.unmodifiableList(Arrays.asList(
            "view", "edit", "download", "upload", "share_internal", "share_external"));

    public static final String VIEW = "view";
    public static final String EDIT = "edit";
    public static final String DOWNLOAD = "download";
    public static final String UPLOAD = "upload";
    public static final String SHARE_INTERNAL = "share_internal";
    public static final String SHARE_EXTERNAL = "share_external";

    private static final Map<String, String> LABELS = new LinkedHashMap<>();
    /** 旧版权限位（delete / share）归并到新版四位，保证历史数据可用 */
    private static final Map<String, String> ALIAS = new HashMap<>();

    static {
        LABELS.put("view", "查看");
        LABELS.put("edit", "编辑");
        LABELS.put("download", "下载");
        LABELS.put("upload", "上传");
        LABELS.put("share_internal", "内部分享");
        LABELS.put("share_external", "外部分享");
        ALIAS.put("delete", EDIT);
        ALIAS.put("share", VIEW);
        ALIAS.put("read", VIEW);
        ALIAS.put("write", EDIT);
        ALIAS.put("create", UPLOAD);
    }

    private final PermissionMapper permissionMapper;
    private final UserMapper userMapper;
    private final FileMetaMapper fileMapper;

    public static List<String> defs() { return PERMS; }

    public static String label(String perm) {
        return LABELS.getOrDefault(normalize(perm), perm);
    }

    public static String normalize(String perm) {
        if (perm == null) return VIEW;
        String p = perm.trim().toLowerCase();
        return ALIAS.getOrDefault(p, p);
    }

    public static Set<String> all() { return new LinkedHashSet<>(PERMS); }

    public static Set<String> empty() { return new LinkedHashSet<>(); }

    public boolean isAdmin(Long userId) {
        User u = userMapper.selectById(userId);
        return u != null && Objects.equals(u.getRoleId(), 1L);
    }

    /** 资源所有者（文件/文件夹的 owner_id）——始终全权 */
    public boolean isOwner(Long userId, FileMeta f) {
        return f != null && Objects.equals(f.getOwnerId(), userId);
    }

    /**
     * 用户在某个文件夹上的有效权限。
     * @param folderId 文件夹 ID（0 表示本人的根目录，不参与限制）
     */
    public Set<String> effective(Long userId, Long folderId) {
        if (userId == null) return empty();
        User user = userMapper.selectById(userId);
        if (user == null) return empty();
        if (Objects.equals(user.getRoleId(), 1L)) return all();
        if (folderId == null || folderId <= 0) return all();      // 本人空间根目录不参与限制
        FileMeta folder = fileMapper.selectById(folderId);
        if (folder == null || (folder.getIsDeleted() != null && folder.getIsDeleted() == 1)) return empty();

        Set<String> acc = null;
        for (Long level : chain(folderId)) {          // 根 → 当前，逐层取交集
            Set<String> layer = layerPerms(user, level);
            if (layer == null) continue;              // 该层无规则 → 沿用上级
            acc = acc == null ? layer : intersect(acc, layer);
        }
        if (acc != null) return acc;
        // 没有任何针对该用户的规则：自己的文件夹默认全权，他人文件夹默认不可见
        return Objects.equals(folder.getOwnerId(), userId) ? all() : empty();
    }

    /** 文件取其所在文件夹的权限；文件夹取自身权限 */
    public Set<String> effectiveForFile(Long userId, FileMeta f) {
        if (f == null) return empty();
        if (f.getIsDir() != null && f.getIsDir() == 1) return effective(userId, f.getId());
        Long pid = f.getParentId();
        if (pid == null || pid <= 0) {
            // 根目录不参与授权：根目录下的文件只有所有者可见
            return Objects.equals(f.getOwnerId(), userId) ? all() : empty();
        }
        return effective(userId, pid);
    }

    public boolean can(Long userId, Long folderId, String perm) {
        return effective(userId, folderId).contains(normalize(perm));
    }

    public boolean canAccess(Long userId, FileMeta f, String perm) {
        return effectiveForFile(userId, f).contains(normalize(perm));
    }

    public void require(Long userId, Long folderId, String perm) {
        if (!can(userId, folderId, perm)) {
            throw new BizException(403, "无" + label(perm) + "权限");
        }
    }

    /**
     * 校验对某个文件的权限并返回文件实体。
     * 注意：不再要求“必须是所有者”，被授权用户同样可以查看/编辑/下载。
     */
    public FileMeta requireFile(Long userId, Long fileId, String perm) {
        FileMeta f = fileMapper.selectById(fileId);
        if (f == null || (f.getIsDeleted() != null && f.getIsDeleted() == 1)) {
            throw new BizException(404, "文件不存在");
        }
        if (!canAccess(userId, f, perm)) {
            throw new BizException(403, "无" + label(perm) + "权限");
        }
        return f;
    }

    /**
     * 目标目录的存储归属者：上传/新建文件夹时，文件归目录所有者所有，
     * 这样被授权者写入的内容对目录所有者立即可见，也不会出现“同一目录两种所有者”的脏数据。
     */
    public Long targetOwner(Long userId, Long parentId) {
        if (parentId == null || parentId == 0) return userId;
        FileMeta folder = fileMapper.selectById(parentId);
        if (folder == null || (folder.getIsDeleted() != null && folder.getIsDeleted() == 1)) {
            throw new BizException(404, "目标文件夹不存在");
        }
        if (folder.getIsDir() == null || folder.getIsDir() != 1) {
            throw new BizException(400, "目标不是文件夹");
        }
        return folder.getOwnerId();
    }

    /**
     * “共享给我的”入口文件夹：被授权的、且其上级不可达的文件夹。
     * 只返回顶层入口，避免同一棵子树在列表里重复出现。
     */
    public List<FileMeta> grantedFolders(Long userId) {
        if (userId == null) return Collections.emptyList();
        User user = userMapper.selectById(userId);
        if (user == null || Objects.equals(user.getRoleId(), 1L)) return Collections.emptyList();

        Set<Long> candidates = new LinkedHashSet<>();
        for (Permission p : rulesOf("user", userId)) candidates.add(p.getFolderId());
        if (user.getRoleId() != null) {
            for (Permission p : rulesOf("role", user.getRoleId())) candidates.add(p.getFolderId());
        }
        List<FileMeta> out = new ArrayList<>();
        for (Long id : candidates) {
            if (id == null || id <= 0) continue;
            FileMeta f = fileMapper.selectById(id);
            if (f == null || (f.getIsDeleted() != null && f.getIsDeleted() == 1)) continue;
            if (f.getIsDir() == null || f.getIsDir() != 1) continue;
            if (Objects.equals(f.getOwnerId(), userId)) continue;
            if (!effective(userId, id).contains(VIEW)) continue;
            Long parent = f.getParentId();
            if (parent != null && parent > 0 && effective(userId, parent).contains(VIEW)) continue; // 上级已可达
            out.add(f);
        }
        out.sort(Comparator.comparing(FileMeta::getName, Comparator.nullsLast(String::compareTo)));
        return out;
    }

    public List<Permission> rules(String subjectType, Long subjectId) {
        return permissionMapper.selectList(new LambdaQueryWrapper<Permission>()
                .eq(Permission::getSubjectType, subjectType)
                .eq(Permission::getSubjectId, subjectId)
                .orderByAsc(Permission::getFolderId));
    }

    /**
     * 角色的“默认文件夹权限”：仅沿目录链取角色规则的交集，不考虑任何用户规则。
     * 用户“按角色授权”时的权限上限、以及角色默认权限的展示都用这个口径。
     */
    public Set<String> roleEffective(Long roleId, Long folderId) {
        if (roleId == null || folderId == null || folderId <= 0) return empty();
        Set<String> acc = null;
        for (Long level : chain(folderId)) {
            Permission p = find("role", roleId, level);
            if (p == null) continue;
            Set<String> layer = parse(p.getPermBits());
            acc = acc == null ? layer : intersect(acc, layer);
        }
        return acc == null ? empty() : acc;
    }

    /** 角色的默认规则（按目录路径升序），供角色维护界面使用 */
    public List<Permission> roleRules(Long roleId) {
        return rules("role", roleId);
    }

    /**
     * 解绑角色：删除该角色的全部默认规则，并把引用它的用户规则降级为“直接指定”
     * （权限沿用解绑前的上限，避免删角色导致用户权限意外放大或丢失）。
     */
    @Transactional
    public void detachRole(Long roleId) {
        if (roleId == null) return;
        permissionMapper.delete(new LambdaQueryWrapper<Permission>()
                .eq(Permission::getSubjectType, "role")
                .eq(Permission::getSubjectId, roleId));
        permissionMapper.update(null, new LambdaUpdateWrapper<Permission>()
                .eq(Permission::getSourceRoleId, roleId)
                .set(Permission::getSourceRoleId, null));
    }

    private List<Permission> rulesOf(String subjectType, Long subjectId) {
        if (subjectId == null) return Collections.emptyList();
        return permissionMapper.selectList(new LambdaQueryWrapper<Permission>()
                .eq(Permission::getSubjectType, subjectType)
                .eq(Permission::getSubjectId, subjectId));
    }

    /**
     * 保存规则。folderId 必须指向真实文件夹；perms 只接受 view/edit/download/upload。
     * 同一主体 + 同一文件夹只保留一条规则（重复保存即覆盖）。
     */
    @Transactional
    public void save(String subjectType, Long subjectId, Long folderId, Collection<String> perms, Integer inherit) {
        save(subjectType, subjectId, folderId, perms, inherit, null);
    }

    /**
     * 保存规则（带角色来源）。
     * sourceRoleId 只对用户规则有意义：非空表示该规则按角色默认权限实时取值，
     * 此时 perms 作为“授权上限”保存。
     */
    @Transactional
    public void save(String subjectType, Long subjectId, Long folderId, Collection<String> perms,
                     Integer inherit, Long sourceRoleId) {
        if (!"user".equals(subjectType) && !"role".equals(subjectType)) throw new BizException(400, "权限主体不合法");
        if (subjectId == null) throw new BizException(400, "请选择授权对象");
        if (folderId == null || folderId <= 0) throw new BizException(400, "请选择具体的文件夹（根目录不支持单独配置）");
        FileMeta folder = fileMapper.selectById(folderId);
        if (folder == null || (folder.getIsDeleted() != null && folder.getIsDeleted() == 1) || folder.getIsDir() != 1) {
            throw new BizException(404, "文件夹不存在");
        }
        Set<String> clean = new LinkedHashSet<>();
        if (perms != null) for (String p : perms) {
            String n = normalize(p);
            if (PERMS.contains(n)) clean.add(n);
        }
        String bits = String.join(",", clean);
        Long source = "user".equals(subjectType) ? sourceRoleId : null;
        Permission p = find(subjectType, subjectId, folderId);
        if (p == null) {
            p = new Permission();
            p.setSubjectType(subjectType);
            p.setSubjectId(subjectId);
            p.setFolderId(folderId);
            p.setPermBits(bits);
            p.setInherit(inherit == null ? 1 : inherit);
            p.setSourceRoleId(source);
            permissionMapper.insert(p);
        } else {
            // 用显式 set 更新：source_role_id 可能要从“有”改回 null，
            // MyBatis-Plus 默认忽略 null 字段，updateById 清不掉
            permissionMapper.update(null, new LambdaUpdateWrapper<Permission>()
                    .eq(Permission::getId, p.getId())
                    .set(Permission::getPermBits, bits)
                    .set(Permission::getInherit, inherit == null ? 1 : inherit)
                    .set(Permission::getSourceRoleId, source));
        }
    }

    @Transactional
    public void delete(Long id) {
        permissionMapper.deleteById(id);
    }

    /** 文件夹被彻底删除后清理其上的规则，避免残留规则指向已不存在的目录 */
    @Transactional
    public void deleteByFolder(Long folderId) {
        if (folderId == null) return;
        permissionMapper.delete(new LambdaQueryWrapper<Permission>().eq(Permission::getFolderId, folderId));
    }

    // ================= 内部 =================

    /** 目录链（自上而下）；遇到 inherit=0 的规则即停止向上 */
    private List<Long> chain(Long folderId) {
        LinkedList<Long> chain = new LinkedList<>();
        Long cur = folderId;
        int guard = 0;
        while (cur != null && cur > 0 && guard++ < 1000) {
            chain.addFirst(cur);
            if (blockInherit(cur)) break;
            FileMeta f = fileMapper.selectById(cur);
            if (f == null) break;
            cur = f.getParentId();
        }
        return chain;
    }

    private boolean blockInherit(Long folderId) {
        List<Permission> rules = permissionMapper.selectList(new LambdaQueryWrapper<Permission>()
                .eq(Permission::getFolderId, folderId));
        for (Permission p : rules) {
            if (p.getInherit() != null && p.getInherit() == 0) return true;
        }
        return false;
    }

    /**
     * 该层的有效规则：用户规则优先于角色规则；都没有则返回 null（沿用上级）。
     * 用户规则带来源角色时，实际权限 = 角色默认权限 ∩ 授权上限（角色调整后自动跟随）。
     */
    private Set<String> layerPerms(User user, Long folderId) {
        Permission userRule = find("user", user.getId(), folderId);
        if (userRule != null) {
            Set<String> cap = parse(userRule.getPermBits());
            if (userRule.getSourceRoleId() != null) {
                return intersect(roleEffective(userRule.getSourceRoleId(), folderId), cap);
            }
            return cap;
        }
        if (user.getRoleId() != null) {
            Permission roleRule = find("role", user.getRoleId(), folderId);
            if (roleRule != null) return parse(roleRule.getPermBits());
        }
        return null;
    }

    private Permission find(String type, Long subjectId, Long folderId) {
        if (subjectId == null) return null;
        return permissionMapper.selectOne(new LambdaQueryWrapper<Permission>()
                .eq(Permission::getSubjectType, type)
                .eq(Permission::getSubjectId, subjectId)
                .eq(Permission::getFolderId, folderId)
                .last("limit 1"));
    }

    private Set<String> parse(String bits) {
        Set<String> set = new LinkedHashSet<>();
        if (bits != null) for (String s : bits.split(",")) {
            String n = normalize(s);
            if (!n.isEmpty() && PERMS.contains(n)) set.add(n);
        }
        return set;
    }

    private Set<String> intersect(Set<String> a, Set<String> b) {
        Set<String> r = new LinkedHashSet<>();
        for (String p : PERMS) if (a.contains(p) && b.contains(p)) r.add(p);
        return r;
    }
}
