package com.dietguide;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@SpringBootApplication
@ConfigurationPropertiesScan
public class DietGuideApplication {

    public static void main(String[] args) {
        loadDotEnv();
        SpringApplication.run(DietGuideApplication.class, args);
    }

    /**
     * 读取工作目录下的 .env 并注入系统属性，方便本地配置密钥。
     * 已存在的环境变量优先，不会被文件覆盖。
     */
    private static void loadDotEnv() {
        Path file = Path.of(".env");
        if (!Files.isRegularFile(file)) {
            return;
        }
        try {
            List<String> lines = Files.readAllLines(file);
            for (String line : lines) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                int separator = trimmed.indexOf('=');
                if (separator <= 0) {
                    continue;
                }
                String key = trimmed.substring(0, separator).trim();
                String value = trimmed.substring(separator + 1).trim();
                if (System.getenv(key) != null || System.getProperty(key) != null) {
                    continue;
                }
                System.setProperty(key, value);
            }
        } catch (IOException error) {
            System.err.println("读取 .env 失败：" + error.getMessage());
        }
    }
}
