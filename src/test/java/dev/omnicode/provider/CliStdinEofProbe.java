package dev.omnicode.provider;

/** Test-only child process that cannot exit until its parent closes stdin. */
public final class CliStdinEofProbe {
    private CliStdinEofProbe() {}

    public static void main(String[] args) throws Exception {
        if (System.in.read() == -1) {
            System.out.print("EOF");
            return;
        }
        System.exit(2);
    }
}
