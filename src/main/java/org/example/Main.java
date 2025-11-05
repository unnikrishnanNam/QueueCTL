package org.example;

import org.example.cli.QueueCtlCommand;
import picocli.CommandLine;

public class Main {
    public static void main(String[] args) {
        int exitCode = new CommandLine(new QueueCtlCommand()).execute(args);
        System.exit(exitCode);
    }
}