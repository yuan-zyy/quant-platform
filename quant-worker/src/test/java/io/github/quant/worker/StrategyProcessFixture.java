package io.github.quant.worker;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class StrategyProcessFixture {
    public static void main(String[] args) throws Exception {
        var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        switch (args[0]) {
            case "echo" -> {
                String line;
                while ((line = input.readLine()) != null) {
                    System.err.println("strategy diagnostic");
                    System.out.write(line.getBytes(StandardCharsets.UTF_8));
                    System.out.write('\n');
                    System.out.flush();
                }
            }
            case "ignore-input" -> Thread.sleep(60_000);
            case "hang" -> {
                input.readLine();
                Files.writeString(Path.of("worker.pid"), Long.toString(ProcessHandle.current().pid()));
                Thread.sleep(60_000);
            }
            case "oversize" -> {
                input.readLine();
                System.out.println("x".repeat(StrategyProcess.MAX_LINE_BYTES + 1));
            }
            case "malformed" -> {
                input.readLine();
                System.out.write(new byte[]{(byte) 0xc3, '\n'});
                System.out.flush();
            }
            case "truncated" -> {
                input.readLine();
                System.out.print("partial");
                System.out.flush();
            }
            default -> throw new IllegalArgumentException("unknown fixture mode");
        }
    }
}
