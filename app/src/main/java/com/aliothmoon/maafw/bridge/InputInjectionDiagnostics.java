package com.aliothmoon.maafw.bridge;

import android.os.Process;
import android.os.SystemClock;

import com.aliothmoon.maafw.third.Ln;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * InputManager only reports injection as a boolean; capture system state when it is rejected.
 */
final class InputInjectionDiagnostics {

    private static final String TAG = "InputInjectionDiagnostics";
    private static final String[][] DIAGNOSTIC_COMMANDS = {
            {"input", "dumpsys", "input"},
            {"window", "dumpsys", "window"},
            {"activity", "dumpsys", "activity", "activities"},
            {"display", "dumpsys", "display"},
    };
    private static final int MAX_COMMAND_OUTPUT_CHARS = 32 * 1024;
    private static final int MAX_LOG_LINE_CHARS = 1600;
    private static final long COMMAND_TIMEOUT_MILLIS = 5_000;

    private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MaaFw-InputDiagnostics");
        thread.setDaemon(true);
        return thread;
    });
    private static final AtomicBoolean CAPTURED = new AtomicBoolean();

    private InputInjectionDiagnostics() {
    }

    static void capture(String eventKind, String eventSnapshot, String stage, int displayId) {
        String injectionThreadName = Thread.currentThread().getName();
        if (!CAPTURED.compareAndSet(false, true)) {
            return;
        }
        try {
            EXECUTOR.execute(() -> run(
                    eventKind,
                    eventSnapshot,
                    stage,
                    displayId,
                    injectionThreadName
            ));
        } catch (RuntimeException e) {
            Ln.w(TAG + ": could not schedule diagnostics", e);
        }
    }

    private static void run(String eventKind, String eventSnapshot, String stage, int displayId,
                            String injectionThreadName) {
        try {
            long start = SystemClock.elapsedRealtime();
            Ln.w(TAG + ": begin"
                    + " reason=injectInputEventReturnedFalse"
                    + " eventKind=" + eventKind
                    + " stage=" + stage
                    + " displayId=" + displayId
                    + " pid=" + Process.myPid()
                    + " injectionThread=" + injectionThreadName
                    + " diagnosticsThread=" + Thread.currentThread().getName()
                    + " uptimeMillis=" + SystemClock.uptimeMillis()
                    + " event=" + eventSnapshot);

            for (String[] spec : DIAGNOSTIC_COMMANDS) {
                captureService(spec);
            }

            Ln.w(TAG + ": end elapsedMillis=" + (SystemClock.elapsedRealtime() - start));
        } finally {
            EXECUTOR.shutdown();
        }
    }

    private static void captureService(String[] spec) {
        String service = spec[0];
        String[] command = new String[spec.length - 1];
        System.arraycopy(spec, 1, command, 0, command.length);
        long start = SystemClock.elapsedRealtime();
        try {
            CommandOutput output = runCommand(command);
            Ln.w(TAG + ": dump begin service=" + service
                    + " exitCode=" + output.exitCode
                    + " truncated=" + output.truncated
                    + " chars=" + output.output.length());
            logLines(service, output.output);
            Ln.w(TAG + ": dump end service=" + service
                    + " elapsedMillis=" + (SystemClock.elapsedRealtime() - start));
        } catch (IOException | InterruptedException | RuntimeException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            Ln.w(TAG + ": dump failed service=" + service
                    + " elapsedMillis=" + (SystemClock.elapsedRealtime() - start), e);
        }
    }

    private static CommandOutput runCommand(String[] command) throws IOException, InterruptedException {
        java.lang.Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
        AtomicBoolean completed = new AtomicBoolean();
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(COMMAND_TIMEOUT_MILLIS);
                if (!completed.get()) {
                    process.destroy();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "MaaFw-InputDiagnostics-Watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        StringBuilder output = new StringBuilder();
        char[] buffer = new char[4096];
        long discardedChars = 0;
        boolean truncated = false;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            while (output.length() < MAX_COMMAND_OUTPUT_CHARS) {
                int length = reader.read(buffer, 0, Math.min(
                        buffer.length,
                        MAX_COMMAND_OUTPUT_CHARS - output.length()
                ));
                if (length < 0) {
                    break;
                }
                output.append(buffer, 0, length);
            }
            if (output.length() == MAX_COMMAND_OUTPUT_CHARS) {
                int length;
                while ((length = reader.read(buffer)) >= 0) {
                    discardedChars += length;
                }
                truncated = true;
            }
        }
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            completed.set(true);
            Thread.currentThread().interrupt();
            throw e;
        }
        completed.set(true);
        watchdog.interrupt();
        if (discardedChars > 0) {
            Ln.w(TAG + ": dump discardedChars=" + discardedChars);
        }
        return new CommandOutput(output.toString(), exitCode, truncated);
    }

    private static void logLines(String service, String output) {
        int lineNumber = 0;
        int start = 0;
        while (start < output.length()) {
            int end = output.indexOf('\n', start);
            if (end < 0) {
                end = output.length();
            }
            logLine(service, ++lineNumber, output.substring(start, end));
            start = end + 1;
        }
    }

    private static void logLine(String service, int lineNumber, String line) {
        String prefix = TAG + ": dump service=" + service + " line=" + lineNumber + " ";
        int start = 0;
        while (start < line.length()) {
            int end = Math.min(line.length(), start + MAX_LOG_LINE_CHARS);
            Ln.w(prefix + line.substring(start, end));
            start = end;
        }
    }

    private static final class CommandOutput {
        private final String output;
        private final int exitCode;
        private final boolean truncated;

        private CommandOutput(String output, int exitCode, boolean truncated) {
            this.output = output;
            this.exitCode = exitCode;
            this.truncated = truncated;
        }
    }
}
