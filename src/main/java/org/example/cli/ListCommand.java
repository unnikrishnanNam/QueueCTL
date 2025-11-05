package org.example.cli;

import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

@Command(name = "list", description = "List jobs by state.")
public class ListCommand implements Runnable {
    @Option(names = "--state", description = "Job state to filter (pending, running, etc.)")
    String state;

    @Override
    public void run() {
        System.out.println("[list] Jobs with state: " + state);
    }
}