package com.bluecode.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.bluecode.skill.ActiveSkills;
import com.bluecode.skill.LoadSkillTool;
import com.bluecode.skill.SkillCatalog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RegistryTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @TempDir
    Path tempDir;

    @Test
    void definitionsReturnsSevenOrdered() {
        Registry registry = Registry.createDefault();

        List<Map<String, Object>> definitions = registry.definitions();

        assertEquals(7, registry.count());
        assertEquals(7, definitions.size());
        assertEquals("ReadFile", definitions.get(0).get("name"));
        assertEquals("WriteFile", definitions.get(1).get("name"));
        assertEquals("EditFile", definitions.get(2).get("name"));
        assertEquals("Bash", definitions.get(3).get("name"));
        assertEquals("Glob", definitions.get(4).get("name"));
        assertEquals("Grep", definitions.get(5).get("name"));
        assertEquals("ListDirectory", definitions.get(6).get("name"));
        assertTrue(registry.get("ReadFile").isPresent());
        assertTrue(registry.get("Missing").isEmpty());
    }

    @Test
    void readOnlyDefinitionsReturnOnlySafeTools() {
        Registry registry = Registry.createDefault();

        assertEquals(List.of("ReadFile", "Glob", "Grep", "ListDirectory"),
                registry.readOnlyDefinitions().stream().map(tool -> (String) tool.get("name")).toList());
        assertTrue(registry.isReadOnly("ReadFile"));
        assertFalse(registry.isReadOnly("WriteFile"));
        assertFalse(registry.isReadOnly("Missing"));
    }

    @Test
    void filteredDefinitionsKeepSystemTools() {
        Registry registry = Registry.createDefault();
        registry.register(new LoadSkillTool(new SkillCatalog(), new ActiveSkills()));

        List<String> filtered = registry.definitionsFiltered(List.of("ReadFile"))
                .stream()
                .map(tool -> (String) tool.get("name"))
                .toList();

        assertEquals(List.of("ReadFile", "LoadSkill"), filtered);
        assertEquals(List.of("LoadSkill"),
                registry.systemDefinitions().stream().map(tool -> (String) tool.get("name")).toList());
    }

    @Test
    void readFileExistsWithLineNumbers() throws Exception {
        Path file = tempDir.resolve("hello.txt");
        Files.writeString(file, "alpha\nbeta");

        Result result = Registry.createDefault().execute("ReadFile", json(Map.of("path", file.toString())));

        assertFalse(result.isError());
        assertTrue(result.content().contains("1\talpha"));
        assertTrue(result.content().contains("2\tbeta"));
    }

    @Test
    void readFileMissingReturnsError() throws Exception {
        Result result = Registry.createDefault().execute("ReadFile", json(Map.of("path", tempDir.resolve("missing.txt").toString())));

        assertTrue(result.isError());
        assertTrue(result.content().contains("文件不存在"));
    }

    @Test
    void writeFileCreatesParentDirectories() throws Exception {
        Path file = tempDir.resolve("a/b/c.txt");

        Result result = Registry.createDefault().execute("WriteFile", json(Map.of("path", file.toString(), "content", "hello")));

        assertFalse(result.isError());
        assertEquals("hello", Files.readString(file));
    }

    @Test
    void editFileHandlesUniqueZeroAndMultipleMatches() throws Exception {
        Registry registry = Registry.createDefault();
        Path unique = tempDir.resolve("unique.txt");
        Files.writeString(unique, "one two three");
        Result ok = registry.execute("EditFile", json(Map.of("path", unique.toString(), "old_string", "two", "new_string", "TWO")));
        assertFalse(ok.isError());
        assertEquals("one TWO three", Files.readString(unique));

        Result zero = registry.execute("EditFile", json(Map.of("path", unique.toString(), "old_string", "missing", "new_string", "x")));
        assertTrue(zero.isError());
        assertTrue(zero.content().contains("未找到"));

        Path multiple = tempDir.resolve("multiple.txt");
        Files.writeString(multiple, "cat cat cat");
        Result many = registry.execute("EditFile", json(Map.of("path", multiple.toString(), "old_string", "cat", "new_string", "dog")));
        assertTrue(many.isError());
        assertTrue(many.content().contains("3"));
    }

    @Test
    void bashEchoAndTimeout() {
        Result echo = Registry.createDefault().execute("Bash", "{\"command\":\"echo hi\"}");
        assertFalse(echo.isError());
        assertTrue(echo.content().contains("hi"));
        assertTrue(echo.content().contains("exit_code: 0"));

        String slow = isWindows() ? "ping -n 4 127.0.0.1 > nul" : "sleep 3";
        Result timeout = new BashTool(Duration.ofMillis(100)).execute(Map.of("command", slow));
        assertTrue(timeout.isError());
        assertTrue(timeout.content().contains("命令超时"));
    }

    @Test
    void globStarStarJava() throws Exception {
        Path javaFile = tempDir.resolve("src/main/App.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, "class App {}");
        Files.writeString(tempDir.resolve("README.md"), "# readme");

        Result result = Registry.createDefault().execute("Glob", json(Map.of("path", tempDir.toString(), "pattern", "**/*.java")));

        assertFalse(result.isError());
        assertTrue(result.content().contains("src/main/App.java"));
    }

    @Test
    void grepKeyword() throws Exception {
        Path javaFile = tempDir.resolve("src/main/App.java");
        Files.createDirectories(javaFile.getParent());
        Files.writeString(javaFile, "class App {\n  String keyword = \"BlueCode\";\n}");

        Result result = Registry.createDefault().execute("Grep", json(Map.of("path", tempDir.toString(), "glob", "**/*.java", "pattern", "BlueCode")));

        assertFalse(result.isError());
        assertTrue(result.content().contains("src/main/App.java:2"));
        assertTrue(result.content().contains("BlueCode"));
    }

    private String json(Map<String, String> values) throws JsonProcessingException {
        return MAPPER.writeValueAsString(values);
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
