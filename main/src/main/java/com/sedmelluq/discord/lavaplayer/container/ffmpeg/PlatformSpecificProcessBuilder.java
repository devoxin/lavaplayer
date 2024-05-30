package com.sedmelluq.discord.lavaplayer.container.ffmpeg;

import com.sedmelluq.lava.common.natives.architecture.DefaultOperatingSystemTypes;
import com.sedmelluq.lava.common.natives.architecture.OperatingSystemType;

import java.util.List;

public class PlatformSpecificProcessBuilder {
    private static OperatingSystemType systemType;

    static {
        try {
            systemType = DefaultOperatingSystemTypes.detect();
        } catch (Throwable ignored) {
            systemType = null;
        }
    }

    public static ProcessBuilder getProcessBuilder(String... command) {
        if (systemType != null && "linux".equals(systemType.identifier())) {
            return new ProcessBuilder("/bin/bash", "-c", String.join(" ", command));
        }

        return new ProcessBuilder(command);
    }

    public static ProcessBuilder getProcessBuilder(List<String> command) {
        if (systemType != null && "linux".equals(systemType.identifier())) {
            return new ProcessBuilder("/bin/bash", "-c", String.join(" ", command));
        }

        return new ProcessBuilder(command);
    }
}
