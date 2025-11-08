package org.example.cli;

import picocli.CommandLine.Command;

@Command(name = "queuectl", mixinStandardHelpOptions = true, version = "queuectl 1.0", description = "QueueCTL CLI for job queue management.", subcommands = {
        EnqueueCommand.class,
        WorkerCommand.class,
        StatusCommand.class,
        ListCommand.class,
        DlqCommand.class,
        ConfigCommand.class,
        WebServerCommand.class
})
public class QueueCtlCommand implements Runnable {
    @Override
    public void run() {
        System.out.println("Welcome to QueueCTL CLI. Use --help for usage.");
    }
}