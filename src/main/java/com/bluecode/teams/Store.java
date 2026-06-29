package com.bluecode.teams;

import com.bluecode.team.Persistence;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

public final class Store {
    private final Path path;
    private final ReentrantLock lock = new ReentrantLock();

    public Store(Path path) throws IOException {
        this.path = path.toAbsolutePath().normalize();
        if (this.path.getParent() != null) {
            Files.createDirectories(this.path.getParent());
        }
    }

    public String create(Task task) throws IOException {
        lock.lock();
        try (AutoCloseable ignored = FileLock.acquire(lockPath())) {
            State state = readState();
            Map<String, Task> tasks = toMap(state.tasks());
            String id;
            do {
                id = "task_%06x".formatted(ThreadLocalRandom.current().nextInt(0x1000000));
            } while (tasks.containsKey(id));
            long now = System.currentTimeMillis();
            Task created = new Task(
                    id,
                    task.title(),
                    task.description(),
                    task.status(),
                    task.assignee(),
                    task.blockedBy(),
                    task.blocks(),
                    now,
                    now);
            tasks.put(id, created);
            writeState(tasks);
            return id;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("创建 Team 任务失败", e);
        } finally {
            lock.unlock();
        }
    }

    public Optional<Task> get(String id) throws IOException {
        lock.lock();
        try {
            return Optional.ofNullable(toMap(readState().tasks()).get(id));
        } finally {
            lock.unlock();
        }
    }

    public List<TaskView> list(Filter filter) throws IOException {
        lock.lock();
        try {
            Map<String, Task> tasks = toMap(readState().tasks());
            Optional<Status> status = filter == null ? Optional.empty() : filter.status();
            return tasks.values().stream()
                    .filter(task -> status.isEmpty() || task.status() == status.get())
                    .map(task -> new TaskView(task, isReady(task, tasks)))
                    .toList();
        } finally {
            lock.unlock();
        }
    }

    public void update(String id, Patch patch) throws IOException {
        lock.lock();
        try (AutoCloseable ignored = FileLock.acquire(lockPath())) {
            Map<String, Task> tasks = toMap(readState().tasks());
            Task current = tasks.get(id);
            if (current == null) {
                throw new NoSuchElementException("找不到 Team 任务: " + id);
            }
            Task changed = apply(current, patch);
            tasks.put(id, changed);
            maintainEdges(id, patch, tasks);
            writeState(tasks);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            if (e instanceof NoSuchElementException noSuchElement) {
                throw noSuchElement;
            }
            throw new IOException("更新 Team 任务失败: " + id, e);
        } finally {
            lock.unlock();
        }
    }

    public Path path() {
        return path;
    }

    private Task apply(Task task, Patch patch) {
        Patch p = patch == null
                ? new Patch(Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                List.of(), List.of(), List.of(), List.of())
                : patch;
        List<String> blocks = mutate(task.blocks(), p.addBlocks(), p.removeBlocks());
        List<String> blockedBy = mutate(task.blockedBy(), p.addBlockedBy(), p.removeBlockedBy());
        return new Task(
                task.id(),
                p.title().orElse(task.title()),
                p.description().orElse(task.description()),
                p.status().orElse(task.status()),
                p.assignee().orElse(task.assignee()),
                blockedBy,
                blocks,
                task.createdAt(),
                System.currentTimeMillis());
    }

    private void maintainEdges(String id, Patch patch, Map<String, Task> tasks) {
        if (patch == null) {
            return;
        }
        for (String other : patch.addBlockedBy()) {
            tasks.computeIfPresent(other, (key, value) -> withBlocks(value, add(value.blocks(), id)));
        }
        for (String other : patch.removeBlockedBy()) {
            tasks.computeIfPresent(other, (key, value) -> withBlocks(value, remove(value.blocks(), id)));
        }
        for (String other : patch.addBlocks()) {
            tasks.computeIfPresent(other, (key, value) -> withBlockedBy(value, add(value.blockedBy(), id)));
        }
        for (String other : patch.removeBlocks()) {
            tasks.computeIfPresent(other, (key, value) -> withBlockedBy(value, remove(value.blockedBy(), id)));
        }
    }

    private boolean isReady(Task task, Map<String, Task> tasks) {
        for (String dependency : task.blockedBy()) {
            Task dep = tasks.get(dependency);
            if (dep == null || dep.status() != Status.COMPLETED) {
                return false;
            }
        }
        return true;
    }

    private State readState() throws IOException {
        return Persistence.readJson(path, State.class).orElseGet(() -> new State(List.of()));
    }

    private void writeState(Map<String, Task> tasks) throws IOException {
        Persistence.atomicWriteJson(path, new State(new ArrayList<>(tasks.values())));
    }

    private Map<String, Task> toMap(List<Task> tasks) {
        Map<String, Task> result = new LinkedHashMap<>();
        for (Task task : tasks == null ? List.<Task>of() : tasks) {
            result.put(task.id(), task);
        }
        return result;
    }

    private Path lockPath() {
        return path.resolveSibling(path.getFileName() + ".lock");
    }

    private static List<String> mutate(List<String> original, List<String> add, List<String> remove) {
        List<String> result = new ArrayList<>(original == null ? List.of() : original);
        for (String value : add == null ? List.<String>of() : add) {
            if (value != null && !value.isBlank() && !result.contains(value)) {
                result.add(value);
            }
        }
        for (String value : remove == null ? List.<String>of() : remove) {
            result.remove(value);
        }
        return List.copyOf(result);
    }

    private static List<String> add(List<String> values, String value) {
        return mutate(values, List.of(value), List.of());
    }

    private static List<String> remove(List<String> values, String value) {
        return mutate(values, List.of(), List.of(value));
    }

    private static Task withBlocks(Task task, List<String> blocks) {
        return new Task(task.id(), task.title(), task.description(), task.status(), task.assignee(),
                task.blockedBy(), blocks, task.createdAt(), System.currentTimeMillis());
    }

    private static Task withBlockedBy(Task task, List<String> blockedBy) {
        return new Task(task.id(), task.title(), task.description(), task.status(), task.assignee(),
                blockedBy, task.blocks(), task.createdAt(), System.currentTimeMillis());
    }

    private record State(@JsonProperty("tasks") List<Task> tasks) {
        private State {
            tasks = tasks == null ? List.of() : List.copyOf(tasks);
        }
    }

    public record TaskView(Task task, boolean isReady) {
    }
}
