/*
 * Copyright (c) "Neo4j"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.shell.startup;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.neo4j.shell.Main;

public class CypherShellBoot {

    /**
     * IMPORTANT NOTE!
     * The only purpose of this class is just to forward to the actual main. Its part of a multi-version jar
     * to be able to provide a useful error message when used on an old and unsupported version of java.
     */
    public static void main(String[] args) throws IOException, InterruptedException {
        jvmCheck();

        final var processBuilder = new ProcessBuilder(command(args));
        processBuilder.inheritIO();
        processBuilder.environment().putAll(environment());

        Process process = null;
        int exitCode;
        try {
            process = processBuilder.start();
            exitCode = process.waitFor();
        } catch (Exception e) {
            if (process != null) {
                process.destroy();
            }
            throw e;
        }

        System.exit(exitCode);
    }

    private static void jvmCheck() {
        if (Runtime.version().feature() < 17) {
            System.err.println(
                    "You are using an unsupported version of the Java runtime. Please use Oracle(R) Java(TM) 17 or OpenJDK(TM) 17.");
        }
    }

    private static List<String> command(String[] args) {
        final String javaCommand = ProcessHandle.current()
                .info()
                .command()
                .orElseThrow(() -> new IllegalStateException("Wasn't able to figure out java binary"));
        // The `app.home` property is set by the appassembler-maven-plugin
        final String classPath = System.getProperty("app.home") + File.separator + "lib" + File.separator + "*";

        final var command = new ArrayList<String>();
        command.add(javaCommand);
        command.addAll(jvmInputArguments());
        command.add("-cp");
        command.add(classPath);
        command.add(Main.class.getName());
        command.addAll(Arrays.asList(args));

        return command;
    }

    private static Map<String, String> environment() {
        if (System.getenv() == null) {
            return Map.of();
        }
        return System.getenv();
    }

    private static List<String> jvmInputArguments() {
        final var arguments = new ArrayList<String>();
        arguments.addAll(ManagementFactory.getRuntimeMXBean().getInputArguments());
        arguments.addAll(extraJvmArguments());
        return arguments;
    }

    private static List<String> extraJvmArguments() {
        return List.of(
                "--add-opens", "java.base/java.net=ALL-UNNAMED",
                "--add-opens", "java.base/java.lang=ALL-UNNAMED",
                "--add-opens", "java.base/java.nio=ALL-UNNAMED");
    }
}
