package com.liteark.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * 在 Spring 环境就绪后、任何 Bean（含数据源）创建之前创建数据目录。
 * SQLite 要求数据库文件的父目录必须已存在，否则 Hikari 初始化即失败。
 * 放在此处可同时覆盖外置配置与命令行参数覆盖的场景。
 */
@Order
public class DataDirPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String dataDir = environment.getProperty("liteark.data-dir", "./data");
        String filesDir = environment.getProperty("liteark.files-dir", dataDir + "/files");
        String tempDir = environment.getProperty("liteark.temp-dir", dataDir + "/temp");
        for (String dir : new String[]{dataDir, filesDir, tempDir}) {
            try {
                Files.createDirectories(Paths.get(dir));
            } catch (Exception e) {
                throw new IllegalStateException("无法创建数据目录: " + dir, e);
            }
        }
    }
}
