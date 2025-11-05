package org.example.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

@Command(name = "config", description = "Manage configuration.", subcommands = { ConfigCommand.Set.class })
public class ConfigCommand implements Runnable {
    @Override
    public void run() {
        System.out.println("config [set <key> <value>]");
    }

    @Command(name = "set", description = "Set configuration value.")
    static class Set implements Runnable {
        @Parameters(index = "0", description = "Config key")
        String key;

        @Parameters(index = "1", description = "Config value")
        String value;

        @Override
        public void run() {
            System.out.println("[config set] " + key + " = " + value);
        }
    }
}