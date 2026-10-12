package com.berg.orbis.client;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * The operating system's own "choose a folder" dialog, opened as a small helper process (Minecraft no longer ships
 * a native dialog library, and its window is not a desktop window Java could own): Explorer's folder browser on
 * Windows, Finder on macOS, zenity or kdialog on Linux. Completes with the chosen folder, or null when the dialog was
 * cancelled or none could be opened.
 */
public final class FolderPicker {

    private FolderPicker() {
    }

    /**
     * The operating system's "open a file" dialog the same way, showing the files whose name ends with {@code extension} (".osm.pbf")
     * and offering all files too. Completes with the chosen file, or null.
     */
    public static CompletableFuture<Path> chooseFile(String title, Path start, String filterName, String extension) {
        return CompletableFuture.supplyAsync(() -> {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String startPath = start == null ? "" : start.toAbsolutePath().toString();
            List<List<String>> commands;
            if (os.contains("win")) {
                String ps = "Add-Type -AssemblyName System.Windows.Forms;"
                        + "$f = New-Object System.Windows.Forms.Form -Property @{TopMost = $true};"
                        + "$d = New-Object System.Windows.Forms.OpenFileDialog;"
                        + "$d.Title = '" + title.replace("'", "''") + "';"
                        + "$d.Filter = '" + (filterName + " (*" + extension + ")|*" + extension + "|All files (*.*)|*.*").replace("'", "''") + "';"
                        + "$d.InitialDirectory = '" + startPath.replace("'", "''") + "';"
                        + "if ($d.ShowDialog($f) -eq [System.Windows.Forms.DialogResult]::OK) { [Console]::Out.Write($d.FileName) }";
                commands = List.of(List.of("powershell", "-NoProfile", "-STA", "-Command", ps));
            } else if (os.contains("mac")) {
                commands = List.of(List.of("osascript", "-e", "POSIX path of (choose file with prompt \"" + title.replace("\"", "'") + "\")"));
            } else {
                commands = List.of(
                        List.of("zenity", "--file-selection", "--title=" + title, "--file-filter=" + filterName + " | *" + extension,
                                "--file-filter=All files | *", "--filename=" + startPath + "/"),
                        List.of("kdialog", "--getopenfilename", startPath.isEmpty() ? "." : startPath, "*" + extension, "--title", title));
            }
            return run(commands);
        });
    }

    /** Runs the first dialog program that can be started; its output is the chosen path, or nothing when cancelled. */
    private static Path run(List<List<String>> commands) {
        for (List<String> cmd : commands) {
            try {
                Process p = new ProcessBuilder(cmd).redirectErrorStream(false).start();
                String out;
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    out = r.lines().reduce("", String::concat).trim();
                }
                p.waitFor();
                if (!out.isEmpty()) return Path.of(out);
                return null; // the dialog opened and was cancelled
            } catch (Exception e) {
                // this program is not there: try the next
            }
        }
        return null;
    }

    public static CompletableFuture<Path> choose(String title, Path start) {
        return CompletableFuture.supplyAsync(() -> {
            String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
            String startPath = start == null ? "" : start.toAbsolutePath().toString();
            List<List<String>> commands;
            if (os.contains("win")) {
                String ps = "Add-Type -AssemblyName System.Windows.Forms;"
                        + "$f = New-Object System.Windows.Forms.Form -Property @{TopMost = $true};"
                        + "$d = New-Object System.Windows.Forms.FolderBrowserDialog;"
                        + "$d.Description = '" + title.replace("'", "''") + "';"
                        + "$d.ShowNewFolderButton = $true;"
                        + "$d.SelectedPath = '" + startPath.replace("'", "''") + "';"
                        + "if ($d.ShowDialog($f) -eq [System.Windows.Forms.DialogResult]::OK) { [Console]::Out.Write($d.SelectedPath) }";
                commands = List.of(List.of("powershell", "-NoProfile", "-STA", "-Command", ps));
            } else if (os.contains("mac")) {
                commands = List.of(List.of("osascript", "-e", "POSIX path of (choose folder with prompt \"" + title.replace("\"", "'") + "\")"));
            } else {
                commands = List.of(
                        List.of("zenity", "--file-selection", "--directory", "--title=" + title, "--filename=" + startPath + "/"),
                        List.of("kdialog", "--getexistingdirectory", startPath.isEmpty() ? "." : startPath, "--title", title));
            }
            return run(commands);
        });
    }
}
