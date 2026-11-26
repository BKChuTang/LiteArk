package com.liteark.util;

import com.liteark.config.LiteArkProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Component
@RequiredArgsConstructor
public class FileUtil {
    private final LiteArkProperties props;

    /** 文件实体落盘路径：files/<用户ID>/<随机名>.< ext > */
    public Path storagePath(Long ownerId, String storageKey) {
        return Paths.get(props.getFilesDir(), String.valueOf(ownerId), storageKey).normalize();
    }

    public String randomKey(String ext) {
        String clean = (ext == null || ext.isEmpty()) ? "" : "." + ext.replaceAll("[^a-zA-Z0-9]", "");
        return UUID.randomUUID().toString().replace("-", "") + clean;
    }

    /**
     * 文件名规范化：
     * 1. Unicode NFC 归一，避免 macOS 上传的 NFD 中文名与已有 NFC 名重复；
     * 2. 去掉控制字符与 Windows/Linux 非法字符；
     * 3. 去掉首尾空白与末尾的点（Windows 无法创建以点结尾的文件名）；
     * 4. 超长名截断但保留扩展名（多数文件系统限制 255 字节，中文按 3 字节计）。
     */
    public String safeName(String name) {
        if (name == null) return "未命名";
        String s = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFC);
        s = s.replaceAll("[\\p{Cntrl}]", "")
                .replaceAll("[/\\\\:*?\"<>|]", "_")
                .trim();
        while (s.endsWith(".")) s = s.substring(0, s.length() - 1).trim();
        if (s.isEmpty()) return "未命名";
        if (s.length() > MAX_NAME_LEN) {
            String ext = ext(s);
            int keep = MAX_NAME_LEN - (ext.isEmpty() ? 0 : ext.length() + 1);
            if (keep < 1) keep = 1;
            s = s.substring(0, keep).trim() + (ext.isEmpty() ? "" : "." + ext);
        }
        return s;
    }

    /** 逻辑名长度上限（字符数，非字节数） */
    private static final int MAX_NAME_LEN = 150;

    public static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 && dot < name.length() - 1 ? name.substring(dot + 1).toLowerCase() : "";
    }

    public static String formatSize(long n) {
        if (n <= 0) return "0 B";
        String[] u = {"B", "KB", "MB", "GB", "TB"};
        int i = Math.min((int) (Math.log(n) / Math.log(1024)), u.length - 1);
        return String.format("%.1f %s", n / Math.pow(1024, i), u[i]);
    }
}
