package daikonpp;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
public final class DpRuntime {
    public static final java.nio.file.Path SHM_EX_DIR;
    public static final java.nio.file.Path SHM_FAIL_DIR;
    public static final java.nio.file.Path SHM_CURRENT_DIR;
    public static final java.util.Set<String> SEEN =
        java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    public static final java.util.Set<String> SEEN_FAIL =
        java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
    public static final java.util.Set<String> SEEN_AT_START;
    public static final ThreadLocal<AtomicBoolean> GUARD =
        ThreadLocal.withInitial(() -> new AtomicBoolean(false));
    public static final java.util.Set<String> DISABLED = loadDisabled();
    public static final String INV_DIR = System.getProperty("DP_INV_DIR");
    static {
        String shmBase = System.getProperty("DP_SHM_DIR");
        if (shmBase == null) shmBase = System.getenv("DP_SHM_DIR");
        java.nio.file.Path exDir = null;
        java.nio.file.Path failDir = null;
        java.nio.file.Path currentDir = null;
        if (shmBase != null && !shmBase.trim().isEmpty()) {
            try {
                java.nio.file.Path base = java.nio.file.Paths.get(shmBase);
                exDir = base.resolve("ex");
                failDir = base.resolve("fail");
                currentDir = base.resolve("current");
                java.nio.file.Files.createDirectories(exDir);
                java.nio.file.Files.createDirectories(failDir);
                java.nio.file.Files.createDirectories(currentDir);
                final java.nio.file.Path fEx = exDir;
                try (java.util.stream.Stream<java.nio.file.Path> s =
                        java.nio.file.Files.list(fEx)) {
                    s.forEach(p -> {
                        java.nio.file.Path fn = p.getFileName();
                        if (fn != null) SEEN.add(fn.toString());
                    });
                }
                final java.nio.file.Path fFail = failDir;
                try (java.util.stream.Stream<java.nio.file.Path> s =
                        java.nio.file.Files.list(fFail)) {
                    s.forEach(p -> {
                        java.nio.file.Path fn = p.getFileName();
                        if (fn == null) return;
                        String name = fn.toString();
                        if (name.endsWith(".json"))
                            SEEN_FAIL.add(name.substring(0, name.length() - 5));
                    });
                }
            } catch (Exception ignored) {}
        }
        SEEN_AT_START = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<>(SEEN));
        Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
            public void run() {
                try {
                    String invDir = INV_DIR;
                    if (invDir == null || invDir.trim().length() == 0) return;
                    java.io.File dir = new java.io.File(invDir);
                    dir.mkdirs();
                    java.io.File out = new java.io.File(dir,
                        "dp-events-" + java.util.UUID.randomUUID() + ".log");
                    StringBuilder sb = new StringBuilder();
                    for (String k : SEEN) {
                        sb.append("INV_EXD:").append(k).append('\n');
                    }
                    if (SHM_FAIL_DIR != null) {
                        try (java.util.stream.Stream<java.nio.file.Path> s =
                                java.nio.file.Files.list(SHM_FAIL_DIR)) {
                            s.forEach(p -> {
                                try {
                                    String content = new String(
                                        java.nio.file.Files.readAllBytes(p),
                                        java.nio.charset.StandardCharsets.UTF_8);
                                    if (!content.trim().isEmpty())
                                        sb.append(content.trim()).append('\n');
                                } catch (Exception __ig) {}
                            });
                        } catch (Exception __ig) {}
                    }
                    if (sb.length() > 0) {
                        java.io.OutputStream os = null;
                        try {
                            os = new java.io.FileOutputStream(out, true);
                            os.write(sb.toString().getBytes("UTF-8"));
                        } finally {
                            if (os != null) try { os.close(); } catch (Throwable t) {}
                        }
                    }
                } catch (Throwable __ignore) {}
            }
        }, "dp-sidecar-flush"));
        SHM_EX_DIR = exDir;
        SHM_FAIL_DIR = failDir;
        SHM_CURRENT_DIR = currentDir;
    }
    private static java.util.Set<String> loadDisabled() {
        java.util.Set<String> s =
            java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());
        String f = System.getProperty("DP_DISABLED_FILE");
        if (f == null || f.trim().isEmpty()) f = System.getenv("DP_DISABLED_FILE");
        if (f != null && !f.trim().isEmpty()) {
            try {
                java.nio.file.Path p = java.nio.file.Paths.get(f);
                if (java.nio.file.Files.exists(p)) {
                    for (String line : java.nio.file.Files.readAllLines(p)) {
                        if (line != null && !line.trim().isEmpty()) s.add(line.trim());
                    }
                }
            } catch (Exception ignored) {}
        }
        return s;
    }
    public static void recordExecuted(String uuid) {
        if (SEEN.add(uuid) && SHM_EX_DIR != null) {
            try {
                java.nio.file.Files.createFile(SHM_EX_DIR.resolve(uuid));
            } catch (Exception __ignore) {}
        }
    }
    public static void markCurrent(String uuid) {
        if (SHM_CURRENT_DIR != null) {
            try {
                java.nio.file.Files.createFile(SHM_CURRENT_DIR.resolve(uuid));
            } catch (Exception __ignore) {}
        }
    }
    public static void clearCurrent(String uuid) {
        if (SHM_CURRENT_DIR != null) {
            try {
                java.nio.file.Files.deleteIfExists(SHM_CURRENT_DIR.resolve(uuid));
            } catch (Exception __ignore) {}
        }
    }
    public static void recordFailed(String uuid, String json) {
        if (SEEN_FAIL.add(uuid) && SHM_FAIL_DIR != null) {
            try {
                java.nio.file.Files.write(
                    SHM_FAIL_DIR.resolve(uuid + ".json"),
                    json.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
            } catch (Exception __ignore) {}
        }
    }
    private DpRuntime() {}
}
