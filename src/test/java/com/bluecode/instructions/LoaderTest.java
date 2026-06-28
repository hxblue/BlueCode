package com.bluecode.instructions;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoaderTest {
    @TempDir
    Path tempDir;

    @Test
    void loadsThreeLayersByPriority() throws Exception {
        Path project = tempDir.resolve("project");
        Path user = tempDir.resolve("user");
        write(project.resolve("MEWCODE.md"), "project");
        write(project.resolve(".bluecode/MEWCODE.md"), "project-config");
        write(user.resolve(".bluecode/MEWCODE.md"), "user");

        String loaded = new Loader(project, user, 5).load();

        assertTrue(loaded.indexOf("project") < loaded.indexOf("project-config"));
        assertTrue(loaded.indexOf("project-config") < loaded.indexOf("user"));
    }

    @Test
    void expandsIncludesAndKeepsInlineMentions() throws Exception {
        Path project = tempDir.resolve("project");
        write(project.resolve("rules/style.md"), "style rules");
        write(project.resolve("MEWCODE.md"), "before\n@include rules/style.md\ninline @include ignored");

        String loaded = new Loader(project, tempDir.resolve("user"), 5).load();

        assertTrue(loaded.contains("before\nstyle rules\ninline @include ignored"));
    }

    @Test
    void guardsDepthLoopEscapeAndBinary() throws Exception {
        Path project = tempDir.resolve("project");
        write(project.resolve("d2.md"), "@include d3.md");
        write(project.resolve("d3.md"), "@include d4.md");
        write(project.resolve("d4.md"), "@include d5.md");
        write(project.resolve("d5.md"), "@include d6.md");
        write(project.resolve("d6.md"), "six");
        write(project.resolve("loop.md"), "@include MEWCODE.md");
        write(project.resolve("bin.md"), "\0bad");
        write(tempDir.resolve("outside.md"), "forbidden-content");
        write(project.resolve("MEWCODE.md"), String.join("\n",
                "@include d2.md",
                "@include loop.md",
                "@include ../outside.md",
                "@include bin.md",
                "@include missing.md"));

        String loaded = new Loader(project, tempDir.resolve("user"), 5).load();

        assertTrue(loaded.contains("超过最大嵌套深度"));
        assertTrue(loaded.contains("检测到环路"));
        assertTrue(loaded.contains("路径超出允许范围"));
        assertTrue(loaded.contains("疑似二进制"));
        assertFalse(loaded.contains("six"));
        assertFalse(loaded.contains("forbidden-content"));
    }

    private void write(Path path, String text) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, text, StandardCharsets.UTF_8);
    }
}
