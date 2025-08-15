import java.io.*;
import java.nio.file.*;

public class BotLauncher {
    public static void main(String[] args) throws Exception {
        String python = findPythonExecutable();

        Path tempDir = Files.createTempDirectory("bot-init");
        tempDir.toFile().deleteOnExit();
        Path scriptPath = tempDir.resolve("init.py");

        try (InputStream in = BotLauncher.class.getResourceAsStream("/init.py")) {
            if (in == null) {
                System.err.println("init.py resource not found in JAR.");
                System.exit(2);
            }
            Files.copy(in, scriptPath, StandardCopyOption.REPLACE_EXISTING);
        }
        scriptPath.toFile().deleteOnExit();

        ProcessBuilder pb = new ProcessBuilder(python, scriptPath.toAbsolutePath().toString());
        pb.directory(new File(System.getProperty("user.dir")));
        pb.redirectErrorStream(true);
        Process p = pb.start();

        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            String line;
            while ((line = r.readLine()) != null) {
                System.out.println(line);
            }
        }
        int exit = p.waitFor();
        if (exit != 0) {
            System.err.println("Python process exited with code: " + exit);
        }
        System.exit(exit);
    }

    private static String findPythonExecutable() {
        String[] candidates = {"python3", "python"};
        for (String cmd : candidates) {
            try {
                Process proc = new ProcessBuilder(cmd, "--version").redirectErrorStream(true).start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
                    r.readLine();
                }
                int rc = proc.waitFor();
                if (rc == 0) return cmd;
            } catch (Exception ignored) {
            }
        }
        System.err.println("Python interpreter not found. Please install Python 3 and ensure it is on PATH.");
        System.exit(127);
        return "python3";
    }
}