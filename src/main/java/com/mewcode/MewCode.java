package com.mewcode;

import com.mewcode.config.ConfigLoader;
import com.mewcode.tui.MewCodeModel;
import com.mewcode.tui.tea.Program;

import java.nio.file.Path;

/** MewCode 终端应用入口。 */
public final class MewCode {

    private MewCode() {
    }

    public static void main(String[] args) {
        int exitCode = run();
        if (exitCode != 0) System.exit(exitCode);
    }

    static int run() {
        Path projectRoot = Path.of(System.getProperty("user.dir"))
                .toAbsolutePath()
                .normalize();
        final com.mewcode.config.AppConfig config;
        try {
            config = ConfigLoader.load(projectRoot.resolve(".mewcode/config.yaml"));
        } catch (ConfigLoader.ConfigException error) {
            System.err.println("MewCode: " + error.getMessage());
            return 2;
        }

        var model = new MewCodeModel(config.getProviders(), projectRoot,
                com.mewcode.llm.LlmClients::create, config.getAgent().getLoop());
        var program = new Program(model);

        System.out.print("\033[?25l");
        System.out.flush();
        try {
            program.run();
            return 0;
        } catch (RuntimeException error) {
            System.err.println("MewCode: terminal session failed.");
            return 1;
        } finally {
            System.out.print("\033[?25h");
            System.out.flush();
        }
    }
}
