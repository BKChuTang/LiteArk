package com.liteark.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.liteark.common.BizException;
import com.liteark.config.LiteArkProperties;
import com.liteark.entity.DocVersion;
import com.liteark.entity.FileMeta;
import com.liteark.entity.Share;
import com.liteark.entity.User;
import com.liteark.mapper.DocVersionMapper;
import com.liteark.mapper.FileMetaMapper;
import com.liteark.mapper.ShareMapper;
import com.liteark.mapper.UserMapper;
import com.liteark.util.FileUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class FileService {
    private final FileMetaMapper fileMapper;
    private final DocVersionMapper versionMapper;
    private final ShareMapper shareMapper;
    private final com.liteark.mapper.ShareAccessLogMapper accessLogMapper;
    private final UserMapper userMapper;
    private final FileUtil fileUtil;
    private final LiteArkProperties props;
    private final ThumbnailService thumbService;
    private final PermissionService permService;

    /**
     * 目录列表。parentId=0 时合并“自己的根目录”与“共享给我的文件夹”。
     * 每行附带 perms（当前用户在该项上的有效权限），前端据此收放按钮。
     */
    public List<FileMeta> list(Long userId, Long parentId, String keyword) {
        List<FileMeta> items = new ArrayList<>();
        if (parentId == null || parentId == 0) {
            items.addAll(children(userId, 0L, keyword));
            String kw = keyword == null ? "" : keyword.trim();
            for (FileMeta g : permService.grantedFolders(userId)) {
                if (kw.isEmpty() || (g.getName() != null && g.getName().contains(kw))) items.add(g);
            }
        } else {
            FileMeta folder = fileMapper.selectById(parentId);
            if (folder == null || (folder.getIsDeleted() != null && folder.getIsDeleted() == 1)) {
                throw new BizException(404, "文件夹不存在");
            }
            if (folder.getIsDir() == null || folder.getIsDir() != 1) throw new BizException(400, "目标不是文件夹");
            items.addAll(children(folder.getOwnerId(), parentId, keyword));
        }
        return decorate(userId, items);
    }

    /**
     * 当前所在文件夹的信息：有效权限 + 是否他人共享 + 所有者名称。
     * 前端据此决定展示“共享横幅”和收放上传/新建入口。
     */
    public Map<String, Object> folderInfo(Long userId, Long folderId) {
        Long pid = folderId == null ? 0L : folderId;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("folderId", pid);
        m.put("perms", new ArrayList<>(permService.effective(userId, pid)));
        if (pid <= 0) {
            m.put("name", "全部文件");
            m.put("granted", false);
            m.put("ownerName", "");
            return m;
        }
        FileMeta f = fileMapper.selectById(pid);
        if (f == null || (f.getIsDeleted() != null && f.getIsDeleted() == 1)) {
            m.put("name", "");
            m.put("granted", false);
            m.put("ownerName", "");
            return m;
        }
        boolean granted = !Objects.equals(f.getOwnerId(), userId);
        m.put("name", f.getName());
        m.put("granted", granted);
        m.put("ownerName", granted ? ownerLabel(f.getOwnerId()) : "");
        return m;
    }

    private List<FileMeta> children(Long ownerId, Long parentId, String keyword) {
        LambdaQueryWrapper<FileMeta> q = new LambdaQueryWrapper<FileMeta>()
                .eq(FileMeta::getOwnerId, ownerId)
                .eq(FileMeta::getParentId, parentId)
                .eq(FileMeta::getIsDeleted, 0);
        if (keyword != null && !keyword.trim().isEmpty()) {
            q.like(FileMeta::getName, keyword.trim());
        }
        q.orderByDesc(FileMeta::getIsDir).orderByDesc(FileMeta::getUpdatedAt);
        return fileMapper.selectList(q);
    }

    /** 补齐 perms / ownerName / granted 标记，并过滤掉无查看权限的项 */
    private List<FileMeta> decorate(Long userId, List<FileMeta> items) {
        List<FileMeta> out = new ArrayList<>();
        Map<Long, String> names = new HashMap<>();
        for (FileMeta f : items) {
            Set<String> perms = permService.effectiveForFile(userId, f);
            if (!perms.contains(PermissionService.VIEW)) continue;
            f.setPerms(new ArrayList<>(perms));
            boolean granted = !Objects.equals(f.getOwnerId(), userId);
            f.setGranted(granted);
            if (granted) {
                f.setOwnerName(names.computeIfAbsent(f.getOwnerId(), id -> {
                    User u = userMapper.selectById(id);
                    if (u == null) return "未知用户";
                    return u.getNickname() == null || u.getNickname().isEmpty() ? u.getUsername() : u.getNickname();
                }));
            }
            out.add(f);
        }
        return out;
    }

    /**
     * 目录树：自己的全部文件夹 + 共享给我的入口文件夹（连同其下级）。
     */
    public List<Map<String, Object>> tree(Long userId) {
        List<Map<String, Object>> root = new ArrayList<>();
        Map<String, Object> rootNode = new HashMap<>();
        rootNode.put("id", 0);
        rootNode.put("label", "全部文件");
        List<Map<String, Object>> nodes = new ArrayList<>(buildTree(0L, foldersOf(userId)));
        for (FileMeta g : permService.grantedFolders(userId)) {
            Map<String, Object> n = new HashMap<>();
            n.put("id", g.getId());
            // 目录只显示文件夹名，不挂所有者姓名（是否共享由列表里的「共享」标签与进入后的横幅体现）
            n.put("label", g.getName());
            n.put("granted", true);
            n.put("ownerId", g.getOwnerId());
            n.put("children", buildTree(g.getId(), foldersOf(g.getOwnerId())));
            nodes.add(n);
        }
        rootNode.put("children", nodes);
        root.add(rootNode);
        return root;
    }

    private Map<Long, List<FileMeta>> foldersOf(Long ownerId) {
        List<FileMeta> folders = fileMapper.selectList(new LambdaQueryWrapper<FileMeta>()
                .eq(FileMeta::getOwnerId, ownerId)
                .eq(FileMeta::getIsDir, 1)
                .eq(FileMeta::getIsDeleted, 0));
        return folders.stream().collect(Collectors.groupingBy(FileMeta::getParentId));
    }

    private String ownerLabel(Long ownerId) {
        User u = userMapper.selectById(ownerId);
        if (u == null) return "未知";
        return u.getNickname() == null || u.getNickname().isEmpty() ? u.getUsername() : u.getNickname();
    }

    private List<Map<String, Object>> buildTree(Long parentId, Map<Long, List<FileMeta>> byParent) {
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (FileMeta f : byParent.getOrDefault(parentId, Collections.emptyList())) {
            Map<String, Object> n = new HashMap<>();
            n.put("id", f.getId());
            n.put("label", f.getName());
            n.put("children", buildTree(f.getId(), byParent));
            nodes.add(n);
        }
        return nodes;
    }

    public List<Map<String, Object>> breadcrumb(Long userId, Long id) {
        List<Map<String, Object>> chain = new ArrayList<>();
        Long cur = id;
        int guard = 0;
        while (cur != null && cur > 0 && guard++ < 1000) {
            FileMeta f = fileMapper.selectById(cur);
            if (f == null || !permService.canAccess(userId, f, PermissionService.VIEW)) break;
            Map<String, Object> n = new HashMap<>();
            n.put("id", f.getId());
            n.put("name", f.getName());
            chain.add(0, n);
            cur = f.getParentId();
        }
        Map<String, Object> root = new HashMap<>();
        root.put("id", 0);
        root.put("name", "全部文件");
        chain.add(0, root);
        return chain;
    }

    /** 新建文件夹：归属权归目标目录的所有者（在他人授权目录内建目录，也归目录所有者） */
    public Long createFolder(Long userId, Long parentId, String name) {
        Long pid = parentId == null ? 0L : parentId;
        Long ownerId = permService.targetOwner(userId, pid);
        String safe = fileUtil.safeName(name);
        if (exists(ownerId, pid, safe, null)) throw new BizException("当前目录已存在同名项");
        FileMeta f = new FileMeta();
        f.setOwnerId(ownerId);
        f.setParentId(pid);
        f.setName(safe);
        f.setIsDir(1);
        f.setIsDeleted(0);
        f.setVersion(1);
        fileMapper.insert(f);
        return f.getId();
    }

    public void rename(Long userId, Long id, String name) {
        FileMeta f = writable(userId, id);
        String safe = fileUtil.safeName(name);
        if (exists(f.getOwnerId(), f.getParentId(), safe, id)) throw new BizException("当前目录已存在同名项");
        f.setName(safe);
        f.setUpdatedAt(now());
        fileMapper.updateById(f);
    }

    private boolean exists(Long ownerId, Long parentId, String name, Long excludeId) {
        LambdaQueryWrapper<FileMeta> q = new LambdaQueryWrapper<FileMeta>()
                .eq(FileMeta::getOwnerId, ownerId)
                .eq(FileMeta::getParentId, parentId)
                .eq(FileMeta::getName, name)
                .eq(FileMeta::getIsDeleted, 0);
        if (excludeId != null) q.ne(FileMeta::getId, excludeId);
        return fileMapper.selectCount(q) > 0;
    }

    /** 校验编辑权限并返回实体（不再要求是所有者，被授权用户同样可编辑） */
    private FileMeta writable(Long userId, Long id) {
        return permService.requireFile(userId, id, PermissionService.EDIT);
    }

    private boolean isAncestor(Long ancestorId, Long childId) {
        Long cur = childId;
        int guard = 0;
        while (cur != null && cur > 0 && guard++ < 1000) {
            if (cur.equals(ancestorId)) return true;
            FileMeta f = fileMapper.selectById(cur);
            if (f == null) break;
            cur = f.getParentId();
        }
        return false;
    }

    /** 为复制生成不冲突的名称 */
    private String uniqueName(Long ownerId, Long parentId, String name) {
        if (!exists(ownerId, parentId, name, null)) return name;
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int i = 1; i < 10000; i++) {
            String candidate = base + "(" + i + ")" + ext;
            if (!exists(ownerId, parentId, candidate, null)) return candidate;
        }
        return name + UUID.randomUUID();
    }

    @Transactional
    public int copy(Long userId, List<Long> ids, Long targetId) {
        Long dest = targetId == null ? 0L : targetId;
        Long destOwner = permService.targetOwner(userId, dest);
        int[] counter = {0};
        for (Long id : ids) copyOne(userId, id, dest, destOwner, counter);
        return counter[0];
    }

    private void copyOne(Long userId, Long srcId, Long destParent, Long destOwner, int[] counter) {
        FileMeta src = fileMapper.selectById(srcId);
        if (src == null || (src.getIsDeleted() != null && src.getIsDeleted() == 1)) return;
        if (!permService.canAccess(userId, src, PermissionService.VIEW)) return;
        if (!Objects.equals(src.getOwnerId(), destOwner)) {
            throw new BizException("暂不支持跨用户复制，请先下载后上传到目标目录");
        }
        FileMeta copy = new FileMeta();
        copy.setOwnerId(destOwner);
        copy.setParentId(destParent);
        copy.setName(uniqueName(destOwner, destParent, src.getName()));
        copy.setIsDir(src.getIsDir());
        copy.setExt(src.getExt());
        copy.setSize(src.getSize());
        // 副本必须拥有独立的物理文件：若复用源 storageKey，
        // 彻底删除副本会连带删除源文件实体，且在线编辑一方会改写另一方内容。
        copy.setStorageKey(copyPhysical(src, destOwner));
        copy.setMd5(src.getMd5());
        copy.setVersion(src.getVersion());
        copy.setIsDeleted(0);
        fileMapper.insert(copy);
        counter[0]++;
        if (src.getIsDir() == 1) {
            List<FileMeta> children = fileMapper.selectList(new LambdaQueryWrapper<FileMeta>()
                    .eq(FileMeta::getParentId, srcId).eq(FileMeta::getOwnerId, src.getOwnerId()).eq(FileMeta::getIsDeleted, 0));
            for (FileMeta c : children) copyOne(userId, c.getId(), copy.getId(), destOwner, counter);
        }
    }

    /** 物理复制文件并返回副本专属 storageKey；目录或无实体文件返回 null */
    private String copyPhysical(FileMeta src, Long ownerId) {
        if (src.getIsDir() != null && src.getIsDir() == 1) return null;
        if (src.getStorageKey() == null || src.getStorageKey().isEmpty()) return null;
        Path from = fileUtil.storagePath(src.getOwnerId(), src.getStorageKey());
        if (!Files.exists(from)) throw new BizException(404, "原文件实体丢失：" + src.getName());
        String key = fileUtil.randomKey(src.getExt());
        Path to = fileUtil.storagePath(ownerId, key);
        try {
            Files.createDirectories(to.getParent());
            Files.copy(from, to);
            return key;
        } catch (IOException e) {
            throw new BizException("复制文件失败：" + e.getMessage());
        }
    }

    @Transactional
    public int move(Long userId, List<Long> ids, Long targetId) {
        Long dest = targetId == null ? 0L : targetId;
        Long destOwner = permService.targetOwner(userId, dest);
        for (Long id : ids) {
            FileMeta f = permService.requireFile(userId, id, PermissionService.EDIT);
            if (!Objects.equals(f.getOwnerId(), destOwner)) {
                throw new BizException("暂不支持跨用户移动，请先复制到目标目录后再删除原项");
            }
            if (f.getIsDir() == 1 && isAncestor(id, dest)) throw new BizException("不能将文件夹移动到自身子目录中");
            if (exists(f.getOwnerId(), dest, f.getName(), id)) throw new BizException("目标目录已存在同名项：" + f.getName());
        }
        for (Long id : ids) {
            FileMeta f = fileMapper.selectById(id);
            f.setParentId(dest);
            f.setUpdatedAt(now());
            fileMapper.updateById(f);
        }
        return ids.size();
    }

    @Transactional
    public void recycle(Long userId, List<Long> ids) {
        for (Long id : ids) {
            FileMeta f;
            try {
                f = permService.requireFile(userId, id, PermissionService.EDIT);
            } catch (BizException e) {
                continue;
            }
            f.setIsDeleted(1);
            f.setDeletedAt(now());
            fileMapper.updateById(f);
        }
    }

    public List<FileMeta> recycleList(Long ownerId) {
        return fileMapper.selectList(new LambdaQueryWrapper<FileMeta>()
                .eq(FileMeta::getOwnerId, ownerId).eq(FileMeta::getIsDeleted, 1)
                .orderByDesc(FileMeta::getDeletedAt));
    }

    @Transactional
    public void restore(Long ownerId, Long id) {
        FileMeta f = fileMapper.selectById(id);
        if (f == null || f.getIsDeleted() == null || f.getIsDeleted() != 1 || !Objects.equals(f.getOwnerId(), ownerId)) {
            throw new BizException(404, "回收站中不存在该项");
        }
        // 回收站里的项已标记删除，不能走 requireFile（会按“文件不存在”拒掉），这里直接校验上级目录的编辑权限
        if (!permService.can(ownerId, f.getParentId() == null ? 0L : f.getParentId(), PermissionService.EDIT)) {
            throw new BizException(403, "无编辑权限");
        }
        if (exists(ownerId, f.getParentId(), f.getName(), null)) {
            throw new BizException("原目录已存在同名项，请先重命名");
        }
        f.setIsDeleted(0);
        f.setDeletedAt(null);
        fileMapper.updateById(f);
    }

    @Transactional
    public void permanentDelete(Long ownerId, Long id) {
        FileMeta f = fileMapper.selectById(id);
        if (f == null || !Objects.equals(f.getOwnerId(), ownerId) || f.getIsDeleted() == null || f.getIsDeleted() != 1) {
            throw new BizException(404, "回收站中不存在该项");
        }
        List<FileMeta> subtree = new ArrayList<>();
        collectTree(id, ownerId, subtree);
        // 先子后父，避免上级目录先被删除后下级成为孤儿
        Collections.reverse(subtree);
        long freed = 0;
        for (FileMeta item : subtree) {
            if (item.getIsDir() == 0 && item.getStorageKey() != null) {
                deletePhysicalIfUnused(item, item.getStorageKey());
            }
            if (item.getIsDir() == 0 && item.getSize() != null) freed += item.getSize();
            purge(item);
        }
        releaseUsed(ownerId, freed);
    }

    /** 彻底删除后回收配额；删除到回收站不减，还原也不加，与上传时的累加口径保持一致 */
    private void releaseUsed(Long ownerId, long bytes) {
        if (ownerId == null || bytes <= 0) return;
        User u = userMapper.selectById(ownerId);
        if (u == null) return;
        long cur = u.getUsed() == null ? 0 : u.getUsed();
        u.setUsed(Math.max(0, cur - bytes));
        userMapper.updateById(u);
    }

    /**
     * 彻底删除单条记录前的依赖清理：历史版本（含实体文件）、共享与访问记录、
     * 文件夹授权规则。缺少任一步都会因外键约束导致整个删除事务回滚。
     */
    /**
     * 物理删除 storageKey 对应文件，仅当没有其他 file_meta 行（含回收站中的行）仍引用它。
     * 兼容历史数据中由旧版复制产生、仍共享同一 storageKey 的副本：
     * 只要还有引用就保留物理文件，避免「删副本导致原文件实体丢失」。
     */
    private void deletePhysicalIfUnused(FileMeta item, String key) {
        if (key == null || key.isEmpty()) return;
        List<FileMeta> refs = fileMapper.selectList(new LambdaQueryWrapper<FileMeta>()
                .select(FileMeta::getId).eq(FileMeta::getStorageKey, key)
                .ne(FileMeta::getId, item.getId()));
        if (!refs.isEmpty()) return;
        try { Files.deleteIfExists(fileUtil.storagePath(item.getOwnerId(), key)); } catch (IOException ignored) {}
    }

    private void purge(FileMeta item) {
        for (DocVersion v : versionMapper.selectList(new LambdaQueryWrapper<DocVersion>()
                .eq(DocVersion::getFileId, item.getId()))) {
            if (v.getStorageKey() != null) {
                try { Files.deleteIfExists(fileUtil.storagePath(item.getOwnerId(), v.getStorageKey())); } catch (IOException ignored) {}
            }
            versionMapper.deleteById(v.getId());
        }
        for (Share s : shareMapper.selectList(new LambdaQueryWrapper<Share>().eq(Share::getFileId, item.getId()))) {
            accessLogMapper.delete(new LambdaQueryWrapper<com.liteark.entity.ShareAccessLog>()
                    .eq(com.liteark.entity.ShareAccessLog::getShareId, s.getId()));
            shareMapper.deleteById(s.getId());
        }
        permService.deleteByFolder(item.getId());
        fileMapper.deleteById(item.getId());
    }

    private void collectTree(Long id, Long ownerId, List<FileMeta> acc) {
        FileMeta f = fileMapper.selectById(id);
        if (f == null) return;
        acc.add(f);
        if (f.getIsDir() == 1) {
            List<FileMeta> children = fileMapper.selectList(new LambdaQueryWrapper<FileMeta>()
                    .eq(FileMeta::getParentId, id).eq(FileMeta::getOwnerId, ownerId));
            for (FileMeta c : children) collectTree(c.getId(), ownerId, acc);
        }
    }

    @Transactional
    public void clearRecycle(Long ownerId) {
        List<FileMeta> roots = fileMapper.selectList(new LambdaQueryWrapper<FileMeta>()
                .eq(FileMeta::getOwnerId, ownerId).eq(FileMeta::getIsDeleted, 1));
        // 连同上一次“删除文件夹但下级未同步标记”的残留子项一起清掉
        Map<Long, FileMeta> uniq = new LinkedHashMap<>();
        for (FileMeta r : roots) {
            List<FileMeta> sub = new ArrayList<>();
            collectTree(r.getId(), ownerId, sub);
            for (FileMeta x : sub) uniq.putIfAbsent(x.getId(), x);
        }
        List<FileMeta> all = new ArrayList<>(uniq.values());
        long freed = 0;
        for (FileMeta f : all) {
            if (f.getIsDir() == 0 && f.getStorageKey() != null) {
                deletePhysicalIfUnused(f, f.getStorageKey());
            }
            if (f.getIsDir() == 0 && f.getSize() != null) freed += f.getSize();
        }
        Collections.reverse(all);
        for (FileMeta f : all) purge(f);
        // 兜底：残留行（如上级已删、下级的 is_deleted 未同步）一并清掉
        fileMapper.delete(new LambdaQueryWrapper<FileMeta>()
                .eq(FileMeta::getOwnerId, ownerId).eq(FileMeta::getIsDeleted, 1));
        releaseUsed(ownerId, freed);
    }

    /**
     * 直接上传（小文件）。写入他人授权目录时，文件归属目录所有者，
     * 配额也计入目录所有者，保证目录内数据归属一致。
     */
    @Transactional
    public List<Map<String, Object>> upload(Long userId, Long parentId, List<MultipartFile> files, String clientType) {
        Long pid = parentId == null ? 0L : parentId;
        Long ownerId = permService.targetOwner(userId, pid);
        User user = userMapper.selectById(ownerId);
        String strategy = props.getConflictStrategy();
        List<Map<String, Object>> result = new ArrayList<>();
        long total = 0;
        for (MultipartFile file : files) {
            String name = fileUtil.safeName(file.getOriginalFilename());
            String ext = FileUtil.ext(name);
            FileMeta existing = fileMapper.selectOne(new LambdaQueryWrapper<FileMeta>()
                    .eq(FileMeta::getOwnerId, ownerId).eq(FileMeta::getParentId, pid)
                    .eq(FileMeta::getName, name).eq(FileMeta::getIsDeleted, 0).last("limit 1"));
            if (existing != null && "skip".equalsIgnoreCase(strategy)) {
                Map<String, Object> m = new HashMap<>();
                m.put("name", name);
                m.put("skipped", true);
                result.add(m);
                continue;
            }
            if (existing != null && "rename".equalsIgnoreCase(strategy)) {
                name = uniqueName(ownerId, pid, name);
            }
            if (existing != null && "overwrite".equalsIgnoreCase(strategy)) {
                if (existing.getStorageKey() != null) {
                    try { Files.deleteIfExists(fileUtil.storagePath(ownerId, existing.getStorageKey())); } catch (IOException ignored) {}
                }
                fileMapper.deleteById(existing.getId());
            }
            String key = fileUtil.randomKey(ext);
            Path target = fileUtil.storagePath(ownerId, key);
            try {
                Files.createDirectories(target.getParent());
                file.transferTo(target.toFile());
            } catch (IOException e) {
                throw new BizException("上传失败: " + e.getMessage());
            }
            FileMeta meta = new FileMeta();
            meta.setOwnerId(ownerId);
            meta.setParentId(pid);
            meta.setName(name);
            meta.setIsDir(0);
            meta.setExt(ext);
            meta.setSize(file.getSize());
            meta.setStorageKey(key);
            meta.setIsDeleted(0);
            meta.setVersion(1);
            fileMapper.insert(meta);
            generateThumbnail(ownerId, meta);
            total += file.getSize();
            Map<String, Object> m = new HashMap<>();
            m.put("id", meta.getId());
            m.put("name", name);
            m.put("size", file.getSize());
            result.add(m);
        }
        if (user != null) {
            user.setUsed((user.getUsed() == null ? 0 : user.getUsed()) + total);
            userMapper.updateById(user);
        }
        return result;
    }

    /** 上传图片后同步生成 200px 缩略图，并记录相对路径 */
    public void generateThumbnail(Long ownerId, FileMeta f) {
        try {
            Path thumb = thumbService.get(ownerId, f, 200);
            if (thumb != null) {
                f.setThumbnailPath(Paths.get(props.getDataDir()).relativize(thumb).toString());
                fileMapper.updateById(f);
            }
        } catch (Exception ignored) {}
    }

    /** 物理路径统一按文件实际所有者解析（被授权方访问他人文件时同样正确） */
    public Path physicalPath(Long ownerId, FileMeta f) {
        return fileUtil.storagePath(f.getOwnerId() == null ? ownerId : f.getOwnerId(), f.getStorageKey());
    }

    public Path physicalPath(FileMeta f) {
        return fileUtil.storagePath(f.getOwnerId(), f.getStorageKey());
    }

    @Transactional
    public int saveText(Long userId, Long id, String content) {
        FileMeta f = writable(userId, id);
        Long ownerId = f.getOwnerId();
        Path path = fileUtil.storagePath(ownerId, f.getStorageKey());
        if (!Files.exists(path)) throw new BizException(404, "文件实体丢失");
        try {
            byte[] old = Files.readAllBytes(path);
            String backupKey = fileUtil.randomKey(f.getExt());
            Files.copy(path, fileUtil.storagePath(ownerId, backupKey));
            DocVersion v = new DocVersion();
            v.setFileId(id);
            v.setVersion(f.getVersion());
            v.setStorageKey(backupKey);
            v.setSize((long) old.length);
            versionMapper.insert(v);
            Files.write(path, content == null ? new byte[0] : content.getBytes(StandardCharsets.UTF_8));
            f.setSize((long) (content == null ? 0 : content.getBytes(StandardCharsets.UTF_8).length));
            f.setVersion(f.getVersion() + 1);
            f.setUpdatedAt(now());
            fileMapper.updateById(f);
            return f.getVersion();
        } catch (IOException e) {
            throw new BizException("保存失败: " + e.getMessage());
        }
    }

    public List<DocVersion> versions(Long userId, Long id) {
        permService.requireFile(userId, id, PermissionService.VIEW);
        return versionMapper.selectList(new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<DocVersion>()
                .eq(DocVersion::getFileId, id).orderByDesc(DocVersion::getVersion));
    }

    /** 版本回滚：先把当前内容存为新版本，再把目标版本写回（需求 §3.4） */
    @Transactional
    public int rollback(Long userId, Long fileId, Long versionId) {
        DocVersion target = versionMapper.selectById(versionId);
        if (target == null || !Objects.equals(target.getFileId(), fileId)) throw new BizException(404, "版本不存在");
        FileMeta f = writable(userId, fileId);
        Long ownerId = f.getOwnerId();
        Path path = fileUtil.storagePath(ownerId, f.getStorageKey());
        Path history = fileUtil.storagePath(ownerId, target.getStorageKey());
        if (!Files.exists(path) || !Files.exists(history)) throw new BizException(404, "版本文件实体丢失");
        try {
            String backupKey = fileUtil.randomKey(f.getExt());
            Path backup = fileUtil.storagePath(ownerId, backupKey);
            Files.copy(path, backup);
            DocVersion cur = new DocVersion();
            cur.setFileId(fileId); cur.setVersion(f.getVersion());
            cur.setStorageKey(backupKey); cur.setSize(Files.size(path));
            versionMapper.insert(cur);
            Files.copy(history, path, StandardCopyOption.REPLACE_EXISTING);
            f.setSize(Files.size(path));
            f.setVersion(f.getVersion() + 1);
            f.setUpdatedAt(now());
            fileMapper.updateById(f);
            return f.getVersion();
        } catch (IOException e) {
            throw new BizException("回滚失败: " + e.getMessage());
        }
    }

    private static String now() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date());
    }
}
