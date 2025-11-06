package org.example.cli;

import org.example.core.ConfigRepository;
import org.example.core.Database;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "config", description = "Manage configuration.", subcommands = { ConfigCommand.Set.class,
        ConfigCommand.Get.class })
public class ConfigCommand implements Runnable {
    @Override
    public void run() {
        System.out.println("config [set <key> <value> | get <key>]");
    }

    @Command(name = "set", description = "Set configuration value.")
    static class Set implements Runnable {
        @Parameters(index = "0", description = "Config key")
        String key;

        @Parameters(index = "1", description = "Config value")
        String value;

        @Override
        public void run() {
            Database.init();
            ConfigRepository repo = new ConfigRepository();
            repo.set(key, value);
            System.out.println("[config set] " + key + " = " + value);
        }
    }

    @Command(name = "get", description = "Get configuration value.")
    static class Get implements Runnable {
        @Parameters(index = "0", description = "Config key")
        String key;

        @Override
        public void run() {
            Database.init();
            ConfigRepository repo = new ConfigRepository();
            String value = repo.get(key, null);
            if (value == null) {
                System.out.println("[config get] " + key + " is not set");
            } else {
                System.out.println(value);
            }
        }
    }
}