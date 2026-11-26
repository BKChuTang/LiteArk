package com.liteark.service;

import com.liteark.config.LiteArkProperties;
import com.liteark.entity.FileMeta;
import com.liteark.util.FileUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

/** 服务端图片缩略图（需求 §9 thumbnail_path、§4.5 按端尺寸请求） */
@Slf4j
@Service
@RequiredArgsConstructor
public class ThumbnailService {
    private static final List<String> IMAGE_EXT = Arrays.asList("png", "jpg", "jpeg", "gif", "webp", "bmp");
    private final LiteArkProperties props;
    private final FileUtil fileUtil;

    public static boolean isImage(String ext) {
        return ext != null && IMAGE_EXT.contains(ext.toLowerCase());
    }

    public Path get(Long ownerId, FileMeta f, int width) {
        if (f == null || f.getIsDir() == 1 || !isImage(f.getExt())) return null;
        Path source = fileUtil.storagePath(ownerId, f.getStorageKey());
        if (!Files.exists(source)) return null;
        width = Math.max(48, Math.min(1200, width));
        String base = f.getStorageKey().replaceAll("\\.[^.]+$", "");
        Path target = Paths.get(props.getDataDir(), "thumbnails", String.valueOf(ownerId), String.valueOf(width), base + ".jpg").normalize();
        if (Files.exists(target)) return target;
        try {
            BufferedImage src = ImageIO.read(source.toFile());
            if (src == null) return null;
            int w = Math.min(width, src.getWidth());
            int h = Math.max(1, (int) Math.round(src.getHeight() * (double) w / src.getWidth()));
            BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, w, h);
            g.drawImage(src, 0, 0, w, h, null);
            g.dispose();
            Files.createDirectories(target.getParent());
            ImageIO.write(out, "jpg", target.toFile());
            return target;
        } catch (Exception e) {
            log.debug("缩略图生成失败: {}", e.getMessage());
            return null;
        }
    }
}
