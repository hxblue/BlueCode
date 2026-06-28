package com.bluecode.mcp;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {
    @TempDir
    Path tempDir;

    private String oldHome;
    private Path home;
    private Path root;

    @BeforeEach
    void setUp() throws Exception {
        oldHome = System.getProperty("user.home");
        home = tempDir.resolve("home");
        root = tempDir.resolve("project");
        Files.createDirectories(home.resolve(".bluecode"));
        Files.createDirectories(root);
        System.setProperty("user.home", home.toString());
    }

    @AfterEach
    void tearDown() {
        if (oldHome != null) {
            System.setProperty("user.home", oldHome);
        }
    }

    @Test
    void missingFilesReturnEmptyConfig() {
        McpConfig config = ConfigLoader.loadConfig(root);

        assertTrue(config.servers().isEmpty());
    }

    @Test
    void projectServerOverridesUserServerByName() throws Exception {
        writeUser("""
            mcp_servers:
              shared:
                type: stdio
                command: user-command
              userOnly:
                type: http
                url: "https://user.example/mcp"
            """);
        writeProject("""
            mcp_servers:
              shared:
                type: stdio
                command: project-command
                args: ["--project"]
            """);

        McpConfig config = ConfigLoader.loadConfig(root);

        assertEquals(2, config.servers().size());
        assertEquals("project-command", config.servers().get("shared").command());
        assertEquals("--project", config.servers().get("shared").args().getFirst());
        assertEquals("https://user.example/mcp", config.servers().get("userOnly").url());
    }

    @Test
    void invalidUserFileIsSkippedWithoutBlockingProjectFile() throws Exception {
        writeUser("mcp_servers: [unterminated");
        writeProject("""
            mcp_servers:
              ok:
                type: stdio
                command: echo
            """);

        Capture<McpConfig> capture = captureErr(() -> ConfigLoader.loadConfig(root));

        assertEquals(1, capture.value().servers().size());
        assertTrue(capture.value().servers().containsKey("ok"));
        assertTrue(capture.err().contains("skip config file"));
    }

    @Test
    void expandsOnlyEnvAndHeadersValues() throws Exception {
        Map.Entry<String, String> defined = System.getenv().entrySet().iterator().next();
        String missing = "BLUECODE_MCP_TEST_MISSING";
        writeProject("""
            mcp_servers:
              demo:
                type: http
                url: "https://example.test/mcp"
                headers:
                  X-Defined: "${%s}"
                  X-Missing: "${%s}"
              stdio:
                type: stdio
                command: "${%s}"
                args: ["${%s}"]
                env:
                  TOKEN: "${%s}"
            """.formatted(defined.getKey(), missing, defined.getKey(), defined.getKey(), defined.getKey()));

        Capture<McpConfig> capture = captureErr(() -> ConfigLoader.loadConfig(root));

        ServerConfig demo = capture.value().servers().get("demo");
        ServerConfig stdio = capture.value().servers().get("stdio");
        assertEquals(defined.getValue(), demo.headers().get("X-Defined"));
        assertEquals("", demo.headers().get("X-Missing"));
        assertEquals("${" + defined.getKey() + "}", stdio.command());
        assertEquals("${" + defined.getKey() + "}", stdio.args().getFirst());
        assertEquals(defined.getValue(), stdio.env().get("TOKEN"));
        assertTrue(capture.err().contains("undefined env var ${" + missing + "}"));
    }

    @Test
    void invalidServersAreSkippedIndependently() throws Exception {
        writeProject("""
            mcp_servers:
              missingType:
                command: echo
              badType:
                type: websocket
                url: "ws://example"
              stdioMissingCommand:
                type: stdio
              httpMissingUrl:
                type: http
              ok:
                type: stdio
                command: echo
            """);

        Capture<McpConfig> capture = captureErr(() -> ConfigLoader.loadConfig(root));

        assertEquals(1, capture.value().servers().size());
        assertTrue(capture.value().servers().containsKey("ok"));
        assertTrue(capture.err().contains("skip server missingType"));
        assertTrue(capture.err().contains("skip server badType"));
        assertTrue(capture.err().contains("skip server stdioMissingCommand"));
        assertTrue(capture.err().contains("skip server httpMissingUrl"));
    }

    private void writeUser(String content) throws Exception {
        Files.writeString(home.resolve(".bluecode/config.yaml"), content);
    }

    private void writeProject(String content) throws Exception {
        Files.writeString(root.resolve(".bluecode.yaml"), content);
    }

    private <T> Capture<T> captureErr(ThrowingSupplier<T> supplier) throws Exception {
        PrintStream oldErr = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err, true, StandardCharsets.UTF_8));
        try {
            return new Capture<>(supplier.get(), err.toString(StandardCharsets.UTF_8));
        } finally {
            System.setErr(oldErr);
        }
    }

    private interface ThrowingSupplier<T> {
        T get() throws Exception;
    }

    private record Capture<T>(T value, String err) {
    }
}
