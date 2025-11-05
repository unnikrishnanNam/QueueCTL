package org.example.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "worker", description = "Worker management.", subcommands = { WorkerCommand.Start.class,
        WorkerCommand.Stop.class })
public class WorkerCommand implements Runnable {
    @Override
    public void run() {
        System.out.println("worker [start|stop]");
    }

    @Command(name = "start", description = "Start one or more workers.")
    static class Start implements Runnable {
        @Option(names = "--count", description = "Number of workers to start", defaultValue = "1")
        int count;

        @Override
        public void run() {
            System.out.println("[worker start] count=" + count);
        }
    }

    @Command(name = "stop", description = "Stop running workers gracefully.")
    static class Stop implements Runnable {
        @Override
        public void run() {
            System.out.println("[worker stop]");
        }
    }
}