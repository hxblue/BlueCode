package com.bluecode.skill;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class SkillInstaller {
    public static final long MAX_FILE_SIZE = 1L * 1024L * 1024L;
    public static final long MAX_TOTAL_SIZE = 10L * 1024L * 1024L;
    public static final int MAX_FILE_COUNT = 64;
    public static final int MAX_RECURSION_DEPTH = 8;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private SkillInstaller() {
    }

    public static SkillUrl parseSkillURL(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("skill URL 不能为空");
        }
        URI uri = URI.create(source.strip());
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (host.equals("skills.sh") || host.endsWith(".skills.sh")) {
            String nested = queryParams(uri.getRawQuery()).get("url");
            if (nested != null && !nested.isBlank()) {
                return parseSkillURL(nested);
            }
            return parseSkillsShPath(uri);
        }
        if ("github.com".equals(host) || "www.github.com".equals(host)) {
            return parseGithubTree(uri);
        }
        if ("raw.githubusercontent.com".equals(host)) {
            return parseRawGithub(uri);
        }
        throw new IllegalArgumentException("不支持的 skill URL: " + source);
    }

    public static InstallReport install(SkillUrl source, Path installRoot) throws IOException, InterruptedException {
        if (source == null) {
            throw new IllegalArgumentException("source 不能为空");
        }
        Path root = installRoot == null
                ? Path.of(System.getProperty("user.home", ".")).resolve(".bluecode").resolve("skills")
                : installRoot;
        root = root.toAbsolutePath().normalize();
        Files.createDirectories(root);
        Path staging = Files.createTempDirectory(root, ".install-").toAbsolutePath().normalize();
        DownloadState state = new DownloadState();
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(30))
                    .build();
            downloadDirectory(client, source, source.path(), staging, Path.of(""), state, 0);
            if (!Files.isRegularFile(staging.resolve("SKILL.md"))) {
                throw new IOException("下载内容中没有 SKILL.md");
            }
            String skillName = SkillCatalog.normalizeName(source.skillName());
            if (skillName.isBlank()) {
                skillName = SkillCatalog.normalizeName(staging.getFileName().toString());
            }
            Path destination = root.resolve(skillName).toAbsolutePath().normalize();
            ensureInside(root, destination);
            moveIntoPlace(root, staging, destination);
            return new InstallReport(skillName, destination, state.fileCount, state.totalBytes);
        } catch (IOException | InterruptedException | RuntimeException e) {
            deleteRecursively(root, staging);
            throw e;
        }
    }

    private static SkillUrl parseGithubTree(URI uri) {
        List<String> parts = splitPath(uri.getRawPath());
        if (parts.size() < 5 || !"tree".equals(parts.get(2))) {
            throw new IllegalArgumentException("github.com URL 必须形如 /owner/repo/tree/ref/path");
        }
        String owner = decode(parts.get(0));
        String repo = decode(parts.get(1));
        String ref = decode(parts.get(3));
        String path = joinDecoded(parts, 4);
        return new SkillUrl(owner, repo, ref, path, skillNameFromPath(path, repo));
    }

    private static SkillUrl parseRawGithub(URI uri) {
        List<String> parts = splitPath(uri.getRawPath());
        if (parts.size() < 4) {
            throw new IllegalArgumentException("raw.githubusercontent.com URL 必须包含 owner/repo/ref/path");
        }
        String owner = decode(parts.get(0));
        String repo = decode(parts.get(1));
        String ref = decode(parts.get(2));
        String filePath = joinDecoded(parts, 3);
        String rootPath = stripKnownSkillFile(filePath);
        return new SkillUrl(owner, repo, ref, rootPath, skillNameFromPath(rootPath, repo));
    }

    private static SkillUrl parseSkillsShPath(URI uri) {
        List<String> parts = splitPath(uri.getRawPath());
        if (parts.size() < 2) {
            throw new IllegalArgumentException("skills.sh URL 必须至少包含 owner/repo");
        }
        String owner = decode(parts.get(0));
        String repo = decode(parts.get(1));
        if (parts.size() >= 5 && "tree".equals(parts.get(2))) {
            String ref = decode(parts.get(3));
            String path = joinDecoded(parts, 4);
            return new SkillUrl(owner, repo, ref, path, skillNameFromPath(path, repo));
        }
        String path = parts.size() > 2 ? joinDecoded(parts, 2) : "";
        return new SkillUrl(owner, repo, "main", path, skillNameFromPath(path, repo));
    }

    private static void downloadDirectory(
            HttpClient client,
            SkillUrl source,
            String apiPath,
            Path staging,
            Path relativeBase,
            DownloadState state,
            int depth) throws IOException, InterruptedException {
        if (depth > MAX_RECURSION_DEPTH) {
            throw new IOException("skill 目录超过最大递归深度 " + MAX_RECURSION_DEPTH);
        }
        JsonNode node = getJson(client, contentsUri(source, apiPath));
        if (node.isArray()) {
            for (JsonNode child : node) {
                String type = child.path("type").asText();
                String name = child.path("name").asText();
                String childPath = child.path("path").asText();
                Path childRelative = relativeBase.resolve(name).normalize();
                if (childRelative.isAbsolute() || childRelative.startsWith("..")) {
                    throw new IOException("GitHub Contents 返回了非法路径: " + childRelative);
                }
                switch (type) {
                    case "dir" -> downloadDirectory(client, source, childPath, staging, childRelative, state, depth + 1);
                    case "file" -> downloadFile(client, child, staging.resolve(childRelative), state);
                    default -> throw new IOException("不支持的 GitHub entry 类型: " + type);
                }
            }
            return;
        }
        if ("file".equals(node.path("type").asText())) {
            String name = node.path("name").asText();
            downloadFile(client, node, staging.resolve(relativeBase).resolve(name).normalize(), state);
            return;
        }
        throw new IOException("GitHub Contents 返回了不可安装的内容");
    }

    private static void downloadFile(HttpClient client, JsonNode fileNode, Path target, DownloadState state)
            throws IOException, InterruptedException {
        long declaredSize = fileNode.path("size").asLong(-1);
        if (declaredSize > MAX_FILE_SIZE) {
            throw new IOException("单文件超过限制: " + fileNode.path("path").asText());
        }
        if (++state.fileCount > MAX_FILE_COUNT) {
            throw new IOException("文件数量超过限制 " + MAX_FILE_COUNT);
        }
        String downloadUrl = fileNode.path("download_url").asText();
        if (downloadUrl == null || downloadUrl.isBlank()) {
            throw new IOException("缺少 download_url: " + fileNode.path("path").asText());
        }
        HttpRequest request = HttpRequest.newBuilder(URI.create(downloadUrl))
                .timeout(Duration.ofSeconds(60))
                .GET()
                .build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("下载文件失败 HTTP " + response.statusCode() + ": " + downloadUrl);
        }
        byte[] bytes = response.body();
        if (bytes.length > MAX_FILE_SIZE) {
            throw new IOException("单文件超过限制: " + fileNode.path("path").asText());
        }
        state.totalBytes += bytes.length;
        if (state.totalBytes > MAX_TOTAL_SIZE) {
            throw new IOException("skill 总大小超过限制 " + MAX_TOTAL_SIZE + " bytes");
        }
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    private static JsonNode getJson(HttpClient client, URI uri) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(60))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "BlueCode-SkillInstaller")
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IOException("GitHub Contents API 请求失败 HTTP " + response.statusCode() + ": " + uri);
        }
        return MAPPER.readTree(response.body());
    }

    private static URI contentsUri(SkillUrl source, String path) {
        StringBuilder builder = new StringBuilder("https://api.github.com/repos/")
                .append(segment(source.owner()))
                .append('/')
                .append(segment(source.repo()))
                .append("/contents");
        if (path != null && !path.isBlank()) {
            for (String part : path.split("/")) {
                if (!part.isBlank()) {
                    builder.append('/').append(segment(part));
                }
            }
        }
        builder.append("?ref=").append(segment(source.ref()));
        return URI.create(builder.toString());
    }

    private static void moveIntoPlace(Path root, Path staging, Path destination) throws IOException {
        Path backup = null;
        if (Files.exists(destination)) {
            backup = root.resolve(".backup-" + destination.getFileName() + "-" + System.nanoTime())
                    .toAbsolutePath()
                    .normalize();
            ensureInside(root, backup);
            move(staging.getFileSystem().provider(), destination, backup);
        }
        try {
            move(staging.getFileSystem().provider(), staging, destination);
            if (backup != null) {
                deleteRecursively(root, backup);
            }
        } catch (IOException e) {
            if (backup != null && !Files.exists(destination) && Files.exists(backup)) {
                move(staging.getFileSystem().provider(), backup, destination);
            }
            throw e;
        }
    }

    private static void move(java.nio.file.spi.FileSystemProvider ignored, Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            Files.move(from, to);
        }
    }

    private static void deleteRecursively(Path root, Path target) throws IOException {
        if (target == null || !Files.exists(target)) {
            return;
        }
        ensureInside(root, target.toAbsolutePath().normalize());
        Files.walkFileTree(target, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void ensureInside(Path root, Path target) throws IOException {
        Path resolvedRoot = root.toAbsolutePath().normalize();
        Path resolvedTarget = target.toAbsolutePath().normalize();
        if (!resolvedTarget.startsWith(resolvedRoot)) {
            throw new IOException("目标路径越过安装目录: " + resolvedTarget);
        }
    }

    private static String stripKnownSkillFile(String filePath) {
        String normalized = filePath == null ? "" : filePath.replace('\\', '/');
        for (String suffix : List.of("/SKILL.md", "/skill.yaml", "/prompt.md")) {
            if (normalized.endsWith(suffix)) {
                return normalized.substring(0, normalized.length() - suffix.length());
            }
        }
        if (normalized.equals("SKILL.md") || normalized.equals("skill.yaml") || normalized.equals("prompt.md")) {
            return "";
        }
        return normalized;
    }

    private static String skillNameFromPath(String path, String fallback) {
        String normalized = path == null ? "" : path.replace('\\', '/');
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        if (normalized.isBlank()) {
            return SkillCatalog.normalizeName(fallback);
        }
        int slash = normalized.lastIndexOf('/');
        return SkillCatalog.normalizeName(slash >= 0 ? normalized.substring(slash + 1) : normalized);
    }

    private static List<String> splitPath(String rawPath) {
        String path = rawPath == null ? "" : rawPath;
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        List<String> parts = new ArrayList<>();
        if (path.isBlank()) {
            return parts;
        }
        for (String part : path.split("/")) {
            if (!part.isBlank()) {
                parts.add(part);
            }
        }
        return parts;
    }

    private static String joinDecoded(List<String> parts, int from) {
        StringBuilder builder = new StringBuilder();
        for (int i = from; i < parts.size(); i++) {
            if (!builder.isEmpty()) {
                builder.append('/');
            }
            builder.append(decode(parts.get(i)));
        }
        return builder.toString();
    }

    private static Map<String, String> queryParams(String rawQuery) {
        Map<String, String> result = new LinkedHashMap<>();
        if (rawQuery == null || rawQuery.isBlank()) {
            return result;
        }
        for (String pair : rawQuery.split("&")) {
            int eq = pair.indexOf('=');
            if (eq < 0) {
                result.put(decode(pair), "");
            } else {
                result.put(decode(pair.substring(0, eq)), decode(pair.substring(eq + 1)));
            }
        }
        return result;
    }

    private static String decode(String value) {
        return URLDecoder.decode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String segment(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static final class DownloadState {
        private int fileCount;
        private long totalBytes;
    }

    public record SkillUrl(String owner, String repo, String ref, String path, String skillName) {
        public SkillUrl {
            owner = owner == null ? "" : owner.strip();
            repo = repo == null ? "" : repo.strip();
            ref = ref == null || ref.isBlank() ? "main" : ref.strip();
            path = path == null ? "" : path.strip();
            skillName = SkillCatalog.normalizeName(skillName);
            if (owner.isBlank() || repo.isBlank()) {
                throw new IllegalArgumentException("GitHub URL 缺少 owner/repo");
            }
        }
    }
}
