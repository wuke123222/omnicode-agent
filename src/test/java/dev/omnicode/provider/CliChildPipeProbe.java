package dev.omnicode.provider;

/**
 * Exits while a short-lived child keeps the inherited stdout pipe open. This models npm/Node
 * cleanup workers and guards the runtime probe against an unbounded post-exit read.
 */
public final class CliChildPipeProbe {
    private CliChildPipeProbe() {}

    public static void main(String[] args) throws Exception {
        String javaRuntime = java.nio.file.Path.of(
                System.getProperty("java.home"), "bin",
                System.getProperty("os.name", "").toLowerCase().contains("windows") ? "java.exe" : "java"
        ).toString();
        String classPath = System.getProperty("java.class.path");
        Process child = new ProcessBuilder(javaRuntime, "-cp", classPath, Child.class.getName())
                .inheritIO()
                .start();
        System.out.println("READY");
        System.out.flush();
    }

    public static final class Child {
        private Child() {}

        public static void main(String[] args) throws Exception {
            Thread.sleep(2_000L);
        }
    }
}
