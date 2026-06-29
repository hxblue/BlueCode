package com.bluecode.teams;

import com.bluecode.team.Persistence;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public final class Mailbox {
    private final Path dir;

    public Mailbox(Path dir) throws IOException {
        this.dir = dir.toAbsolutePath().normalize();
        Files.createDirectories(this.dir);
    }

    public void write(String agentId, Message msg) throws IOException {
        Path messagePath = messagePath(agentId);
        try (AutoCloseable ignored = FileLock.acquire(lockPath(agentId))) {
            State state = readState(messagePath);
            List<Message> messages = new ArrayList<>(state.messages());
            Message effective = msg.timestamp() <= 0 ? msg.withTimestamp(Instant.now().getEpochSecond()) : msg;
            messages.add(effective);
            Persistence.atomicWriteJson(messagePath, new State(messages));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("写 mailbox 失败: " + agentId, e);
        }
    }

    public List<Message> read(String agentId) throws IOException {
        try (AutoCloseable ignored = FileLock.acquire(lockPath(agentId))) {
            return readState(messagePath(agentId)).messages();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("读 mailbox 失败: " + agentId, e);
        }
    }

    public ReadUnreadResult readUnread(String agentId) throws IOException {
        List<Message> messages = read(agentId);
        List<Integer> indices = new ArrayList<>();
        List<Message> unread = new ArrayList<>();
        for (int i = 0; i < messages.size(); i++) {
            if (!messages.get(i).read()) {
                indices.add(i);
                unread.add(messages.get(i));
            }
        }
        return new ReadUnreadResult(indices, unread);
    }

    public void markRead(String agentId, List<Integer> indices) throws IOException {
        if (indices == null || indices.isEmpty()) {
            return;
        }
        Path messagePath = messagePath(agentId);
        try (AutoCloseable ignored = FileLock.acquire(lockPath(agentId))) {
            List<Message> messages = new ArrayList<>(readState(messagePath).messages());
            for (Integer index : indices) {
                if (index != null && index >= 0 && index < messages.size()) {
                    messages.set(index, messages.get(index).withRead(true));
                }
            }
            Persistence.atomicWriteJson(messagePath, new State(messages));
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("标记 mailbox 已读失败: " + agentId, e);
        }
    }

    public Path dir() {
        return dir;
    }

    private State readState(Path path) throws IOException {
        return Persistence.readJson(path, State.class).orElseGet(() -> new State(List.of()));
    }

    private Path messagePath(String agentId) {
        return dir.resolve(agentId + ".json");
    }

    private Path lockPath(String agentId) {
        return dir.resolve(agentId + ".lock");
    }

    private record State(@JsonProperty("messages") List<Message> messages) {
        private State {
            messages = messages == null ? List.of() : List.copyOf(messages);
        }
    }
}
