package com.bluecode.prompt;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnvironmentTest {
    @TempDir
    Path tempDir;

    @Test
    void rendersEnvironmentAndDegradesOutsideGitRepository() {
        String oldUserDir = java.lang.System.getProperty("user.dir");
        try {
            java.lang.System.setProperty("user.dir", tempDir.toString());

            Environment environment = Environment.gather("test-version", "test-model");
            String rendered = environment.render();

            assertEquals(tempDir.toString(), environment.workingDir());
            assertTrue(environment.gitStatus().isBlank());
            assertTrue(rendered.contains("工作目录: " + tempDir));
            assertTrue(rendered.contains("平台: "));
            assertTrue(rendered.contains("当前日期: "));
            assertTrue(rendered.contains("应用版本: test-version"));
            assertTrue(rendered.contains("当前模型: test-model"));
        } finally {
            java.lang.System.setProperty("user.dir", oldUserDir);
        }
    }
}
