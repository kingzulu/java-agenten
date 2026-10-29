package com.zuluindustries.agenten.extension;

import com.zuluindustries.agenten.Platform;

import java.io.PrintStream;
import java.util.List;

/** Ein Befehl der Kommandozeile aus einem Fachmodul, z. B. {@code --monitor}. */
public interface CliCommand {

    /** Die Option, mit der der Befehl aufgerufen wird, z. B. {@code --monitor}. */
    String option();

    /** Aufrufzeile für die Hilfe, z. B. {@code agenten --monitor}. */
    String usage();

    /**
     * @param arguments Argumente nach der Option
     * @return Exit-Code
     */
    int run(Platform platform, List<String> arguments, PrintStream out, PrintStream err);
}
