package com.bluecode.mcp;

import com.bluecode.tool.Tool;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.client.transport.customizer.McpSyncHttpClientRequestCustomizer;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpSchema;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

public final class McpManager implements AutoCloseable {
    static volatile Duration connectTimeout = Duration.ofSeconds(30);
    static volatile Duration closeTimeout = Duration.ofSeconds(5);
    static volatile ClientFactory clientFactory = SdkClient::open;

    private final Object lock = new Object();
    private final List<Session> sessions = new ArrayList<>();
    private final List<Tool> tools = new ArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    private McpManager() {
    }

    public static McpManager start(McpConfig config, String version) {
        McpManager manager = new McpManager();
        Map<String, ServerConfig> servers = config == null ? Map.of() : config.servers();
        CountDownLatch done = new CountDownLatch(servers.size());
        for (Map.Entry<String, ServerConfig> entry : servers.entrySet()) {
            Thread.startVirtualThread(() -> {
                try {
                    runWithDeadline(active -> connectOne(manager, entry.getKey(), entry.getValue(), version, active),
                            entry.getKey());
                } finally {
                    done.countDown();
                }
            });
        }
        await(done, Duration.ofMillis(Math.max(1, connectTimeout.toMillis() + 1000)));
        synchronized (manager.lock) {
            manager.tools.sort(Comparator.comparing(Tool::name));
        }
        return manager;
    }

    public List<Tool> tools() {
        synchronized (lock) {
            return List.copyOf(tools);
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<Session> snapshot;
        synchronized (lock) {
            snapshot = List.copyOf(sessions);
        }
        CountDownLatch done = new CountDownLatch(snapshot.size());
        for (Session session : snapshot) {
            Thread.startVirtualThread(() -> {
                try {
                    session.client().closeGracefully();
                } catch (Exception ignored) {
                    // 退出兜底只要求不拖死主进程，单个 server 关闭失败不继续放大。
                } finally {
                    done.countDown();
                }
            });
        }
        await(done, closeTimeout);
    }

    static Map<String, String> mergeOsEnv(Map<String, String> extra) {
        Map<String, String> merged = new LinkedHashMap<>(System.getenv());
        if (extra != null) {
            merged.putAll(extra);
        }
        return merged;
    }

    static void resetClientFactory() {
        clientFactory = SdkClient::open;
    }

    private static void runWithDeadline(TimedAction action, String serverName) {
        AtomicBoolean active = new AtomicBoolean(true);
        FutureTask<Void> task = new FutureTask<>(() -> {
            action.run(active);
            return null;
        });
        Thread worker = Thread.startVirtualThread(task);
        try {
            task.get(connectTimeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            active.set(false);
            worker.interrupt();
            System.err.printf("[mcp] warn: connect server %s failed: timeout after %d seconds%n",
                    serverName, connectTimeout.toSeconds());
        } catch (InterruptedException e) {
            active.set(false);
            worker.interrupt();
            Thread.currentThread().interrupt();
            System.err.printf("[mcp] warn: connect server %s failed: interrupted%n", serverName);
        } catch (Exception e) {
            active.set(false);
            System.err.printf("[mcp] warn: connect server %s failed: %s%n", serverName, deepestMessage(e));
        }
    }

    private static void connectOne(McpManager manager, String name, ServerConfig config, String version,
                                   AtomicBoolean active) throws Exception {
        ManagedClient client = clientFactory.open(name, config, version == null || version.isBlank() ? "dev" : version);
        boolean keepClient = false;
        try {
            client.initialize();
            List<McpSchema.Tool> remoteTools = client.listTools();
            Map<String, Tool> adapted = new LinkedHashMap<>();
            for (McpSchema.Tool remoteTool : remoteTools) {
                McpTool.adaptTool(name, remoteTool, client).ifPresent(tool -> {
                    if (adapted.containsKey(tool.name())) {
                        System.err.printf("[mcp] warn: duplicate tool %s from server %s, keep latest%n", tool.name(), name);
                    }
                    adapted.put(tool.name(), tool);
                });
            }
            synchronized (manager.lock) {
                if (!active.get()) {
                    return;
                }
                manager.sessions.add(new Session(name, client));
                manager.tools.addAll(adapted.values());
            }
            keepClient = true;
            System.err.printf("[mcp] info: connected server %s with %d tools%n", name, adapted.size());
        } finally {
            if (!keepClient) {
                try {
                    client.closeGracefully();
                } catch (Exception ignored) {
                    // 连接阶段失败后的清理只做 best effort。
                }
            }
        }
    }

    private static void await(CountDownLatch latch, Duration timeout) {
        try {
            latch.await(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String deepestMessage(Throwable e) {
        Throwable current = e;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record Session(String name, ManagedClient client) {
    }

    interface ClientFactory {
        ManagedClient open(String name, ServerConfig config, String version) throws Exception;
    }

    interface ManagedClient extends CallerSession {
        void initialize();

        List<McpSchema.Tool> listTools();

        boolean closeGracefully();
    }

    private interface TimedAction {
        void run(AtomicBoolean active) throws Exception;
    }

    private static final class SdkClient implements ManagedClient {
        private final McpSyncClient client;

        private SdkClient(McpSyncClient client) {
            this.client = client;
        }

        static ManagedClient open(String name, ServerConfig config, String version) {
            return new SdkClient(McpClient.sync(transport(name, config))
                    .requestTimeout(connectTimeout)
                    .initializationTimeout(connectTimeout)
                    .clientInfo(new McpSchema.Implementation("bluecode", version))
                    .build());
        }

        @Override
        public void initialize() {
            client.initialize();
        }

        @Override
        public List<McpSchema.Tool> listTools() {
            McpSchema.ListToolsResult result = client.listTools();
            return result == null || result.tools() == null ? List.of() : result.tools();
        }

        @Override
        public McpSchema.CallToolResult callTool(String name, Map<String, Object> arguments) {
            return client.callTool(McpSchema.CallToolRequest.builder(name)
                    .arguments(arguments == null ? Map.of() : arguments)
                    .build());
        }

        @Override
        public boolean closeGracefully() {
            return client.closeGracefully();
        }

        private static io.modelcontextprotocol.spec.McpClientTransport transport(String name, ServerConfig config) {
            return switch (config.type()) {
                case "stdio" -> stdioTransport(config);
                case "http" -> httpTransport(config);
                default -> throw new IllegalArgumentException("unsupported server type for " + name + ": " + config.type());
            };
        }

        private static StdioClientTransport stdioTransport(ServerConfig config) {
            ServerParameters parameters = ServerParameters.builder(config.command())
                    .args(config.args())
                    .env(mergeOsEnv(config.env()))
                    .build();
            StdioClientTransport transport = new StdioClientTransport(parameters, McpJsonDefaults.getMapper());
            transport.setStdErrorHandler(System.err::println);
            return transport;
        }

        private static HttpClientStreamableHttpTransport httpTransport(ServerConfig config) {
            HttpEndpoint endpoint = HttpEndpoint.parse(config.url());
            McpSyncHttpClientRequestCustomizer customizer = (request, method, uri, body, context) ->
                    config.headers().forEach(request::header);
            return HttpClientStreamableHttpTransport.builder(endpoint.baseUri())
                    .endpoint(endpoint.endpoint())
                    .clientBuilder(HttpClient.newBuilder().connectTimeout(connectTimeout))
                    .httpRequestCustomizer(customizer)
                    .resumableStreams(false)
                    .openConnectionOnStartup(false)
                    .connectTimeout(connectTimeout)
                    .build();
        }
    }

    private record HttpEndpoint(String baseUri, String endpoint) {
        static HttpEndpoint parse(String rawUrl) {
            URI uri = URI.create(rawUrl);
            String base = uri.getScheme() + "://" + uri.getRawAuthority();
            String path = uri.getRawPath() == null || uri.getRawPath().isBlank() ? "/mcp" : uri.getRawPath();
            if (uri.getRawQuery() != null && !uri.getRawQuery().isBlank()) {
                path += "?" + uri.getRawQuery();
            }
            return new HttpEndpoint(base, path);
        }
    }
}
