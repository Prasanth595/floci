package io.github.hectorvent.floci.services.ecs.container;

import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.docker.ContainerBuilder;
import io.github.hectorvent.floci.services.ecs.model.ContainerDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * A container definition's {@code linuxParameters}: checked and stored as RegisterTaskDefinition
 * does, and applied to the container it launches as ECS documents each member:
 * {@code initProcessEnabled} is {@code --init}, {@code capabilities} are {@code --cap-add} and
 * {@code --cap-drop}, {@code devices} are {@code --device} (read, write and mknod when no
 * permissions are given), {@code sharedMemorySize} is {@code --shm-size} in MiB, each
 * {@code tmpfs} entry is a {@code --tmpfs} of its size in MiB with its mount options,
 * {@code maxSwap} is {@code --memory-swap} as the container's memory plus that many MiB (a
 * container with only a soft limit gets none), and {@code swappiness} is {@code --memory-swappiness},
 * which registration requires with {@code maxSwap} and drops without it, as ECS does.
 *
 * <p>RegisterTaskDefinition keeps the members verbatim among the definition's unparsed members,
 * which is where they are read from, whether the definition came from the API or a template.
 */
public final class EcsLinuxParameters {

    private static final long MIB = 1024L * 1024L;

    /** The one capability a Fargate container may add (KernelCapabilities, "add"). */
    private static final String FARGATE_CAPABILITY = "SYS_PTRACE";

    /** The values KernelCapabilities lists for {@code add} and {@code drop}. */
    private static final Set<String> KERNEL_CAPABILITIES = Set.of(
            "ALL", "AUDIT_CONTROL", "AUDIT_WRITE", "BLOCK_SUSPEND", "CHOWN", "DAC_OVERRIDE",
            "DAC_READ_SEARCH", "FOWNER", "FSETID", "IPC_LOCK", "IPC_OWNER", "KILL", "LEASE",
            "LINUX_IMMUTABLE", "MAC_ADMIN", "MAC_OVERRIDE", "MKNOD", "NET_ADMIN", "NET_BIND_SERVICE",
            "NET_BROADCAST", "NET_RAW", "SETFCAP", "SETGID", "SETPCAP", "SETUID", "SYS_ADMIN",
            "SYS_BOOT", "SYS_CHROOT", "SYS_MODULE", "SYS_NICE", "SYS_PACCT", "SYS_PTRACE", "SYS_RAWIO",
            "SYS_RESOURCE", "SYS_TIME", "SYS_TTY_CONFIG", "SYSLOG", "WAKE_ALARM");

    /** The tmpfs mount options the Tmpfs reference lists, matched as written, before any {@code =value}. */
    private static final Set<String> TMPFS_MOUNT_OPTIONS = Set.of(
            "defaults", "ro", "rw", "suid", "nosuid", "dev", "nodev", "exec", "noexec", "sync", "async",
            "dirsync", "remount", "mand", "nomand", "atime", "noatime", "diratime", "nodiratime", "bind",
            "rbind", "unbindable", "runbindable", "private", "rprivate", "shared", "rshared", "slave",
            "rslave", "relatime", "norelatime", "strictatime", "nostrictatime", "mode", "uid", "gid",
            "nr_inodes", "nr_blocks", "mpol");

    private static final Set<String> DEVICE_PERMISSIONS = Set.of("read", "write", "mknod");

    private EcsLinuxParameters() {
    }

    /**
     * The {@code linuxParameters} rules RegisterTaskDefinition applies whatever the launch type,
     * before Fargate's own, with the messages and in the order measured on AWS (eu-west-1,
     * 2026-10-03 and 2026-10-16): every capability one the KernelCapabilities reference lists, in
     * any case and without the {@code CAP_} prefix; every device with a host path and only read,
     * write and mknod as permissions, as written; every tmpfs mount with a container path, a size
     * above zero and options the reference lists; a shared memory size above zero; and swap with a
     * swappiness, a swap size of zero or more and a swappiness from 0 to 100.
     */
    public static void validate(List<ContainerDefinition> containerDefs) {
        if (containerDefs == null) {
            return;
        }
        for (ContainerDefinition def : containerDefs) {
            Map<?, ?> parameters = of(def);
            if (parameters == null) {
                continue;
            }
            for (String list : List.of("add", "drop")) {
                List<String> unrecognized = new ArrayList<>();
                for (String capability : capabilities(parameters, list)) {
                    if (!KERNEL_CAPABILITIES.contains(capability.toUpperCase(Locale.ROOT))) {
                        unrecognized.add(capability);
                    }
                }
                if (!unrecognized.isEmpty()) {
                    throw new AwsException("ClientException",
                            "Unrecognized Linux capabilities in " + list + ": " + unrecognized, 400);
                }
            }
            for (Map<?, ?> device : entries(parameters.get("devices"))) {
                if (!(device.get("hostPath") instanceof String hostPath) || hostPath.isBlank()) {
                    throw new AwsException("ClientException", "Device cannot have empty hostPath", 400);
                }
                for (String permission : strings(device.get("permissions"))) {
                    if (!DEVICE_PERMISSIONS.contains(permission)) {
                        throw new AwsException("ClientException",
                                "Invalid permission: " + permission + " for device " + hostPath, 400);
                    }
                }
            }
            for (Map<?, ?> mount : entries(parameters.get("tmpfs"))) {
                if (!(mount.get("containerPath") instanceof String containerPath) || containerPath.isBlank()) {
                    throw new AwsException("ClientException",
                            "All tmpfs mounts must have a specified containerPath value.", 400);
                }
                Long size = number(mount.get("size"));
                if (size == null || size <= 0) {
                    throw new AwsException("ClientException", "The container has an invalid tmpfs size value"
                            + " specified. It must be a non-zero, non-negative integer.", 400);
                }
                for (String option : strings(mount.get("mountOptions"))) {
                    String name = option.contains("=") ? option.substring(0, option.indexOf('=')) : option;
                    if (!TMPFS_MOUNT_OPTIONS.contains(name)) {
                        throw new AwsException("ClientException",
                                "The " + option + " tmpfs mount option specified is invalid.", 400);
                    }
                }
            }
            Long sharedMemorySize = number(parameters.get("sharedMemorySize"));
            if (sharedMemorySize != null && sharedMemorySize <= 0) {
                throw new AwsException("ClientException", "The container has an invalid shared memory volume"
                        + " size. It must be a non-zero, non-negative integer.", 400);
            }
            if (parameters.get("maxSwap") != null && parameters.get("swappiness") == null) {
                throw new AwsException("ClientException",
                        "When a container has a swap memory, it must also specify a swappiness value.", 400);
            }
            Long maxSwap = number(parameters.get("maxSwap"));
            if (maxSwap != null) {
                if (maxSwap < 0) {
                    throw new AwsException("ClientException",
                            "The container has an invalid swap memory size. It must be non-negative integer.", 400);
                }
                Long swappiness = number(parameters.get("swappiness"));
                if (swappiness != null && (swappiness < 0 || swappiness > 100)) {
                    throw new AwsException("ClientException",
                            "The container has an invalid swappiness value. It must be between 0 and 100.", 400);
                }
            }
        }
    }

    /**
     * The {@code linuxParameters} Fargate cannot honour, in the order RegisterTaskDefinition
     * checks them on AWS: host devices, the shared memory size, swap, then any capability but
     * {@code SYS_PTRACE}, the only one Fargate adds, matched as written. AWS words these without
     * the trailing period its other Fargate refusals carry.
     */
    public static void validateFargate(ContainerDefinition def) {
        Map<?, ?> parameters = of(def);
        if (parameters == null) {
            return;
        }
        rejectOnFargate(parameters.get("devices") instanceof Collection<?> devices && !devices.isEmpty(),
                "devices");
        rejectOnFargate(parameters.get("sharedMemorySize") != null, "sharedMemorySize");
        rejectOnFargate(parameters.get("maxSwap") != null, "maxSwap");
        rejectOnFargate(parameters.get("swappiness") != null, "swappiness");
        for (String capability : capabilities(parameters, "add")) {
            if (!FARGATE_CAPABILITY.equals(capability)) {
                throw new AwsException("ClientException", capability + " is not allowed on Fargate.", 400);
            }
        }
    }

    /**
     * The {@code linuxParameters} as RegisterTaskDefinition stores them (measured): a swappiness
     * without a swap size is dropped, whatever its value, and capabilities always carry both an
     * {@code add} and a {@code drop} list.
     */
    public static void normalize(List<ContainerDefinition> containerDefs) {
        if (containerDefs == null) {
            return;
        }
        for (ContainerDefinition def : containerDefs) {
            Map<?, ?> parameters = of(def);
            if (parameters == null) {
                continue;
            }
            Map<Object, Object> stored = new LinkedHashMap<>(parameters);
            if (stored.get("maxSwap") == null) {
                stored.remove("swappiness");
            }
            if (stored.get("capabilities") instanceof Map<?, ?> capabilities) {
                Map<Object, Object> lists = new LinkedHashMap<>();
                lists.put("add", capabilities.get("add") instanceof Collection<?> add ? add : List.of());
                lists.put("drop", capabilities.get("drop") instanceof Collection<?> drop ? drop : List.of());
                stored.put("capabilities", lists);
            }
            Map<String, Object> unparsed = new LinkedHashMap<>(def.getUnparsed());
            unparsed.put("linuxParameters", stored);
            def.setUnparsed(unparsed);
        }
    }

    /**
     * @param memoryLimitMb the hard memory limit the container runs under, which {@code maxSwap}
     *        is added to; without one Docker cannot limit swap, so {@code maxSwap} is not applied
     */
    static void apply(ContainerBuilder.Builder spec, ContainerDefinition def, Integer memoryLimitMb) {
        Map<?, ?> parameters = of(def);
        if (parameters == null) {
            return;
        }
        if (Boolean.TRUE.equals(parameters.get("initProcessEnabled"))) {
            spec.withInit();
        }
        capabilities(parameters, "add").forEach(spec::withCapAdd);
        capabilities(parameters, "drop").forEach(spec::withCapDrop);
        for (Map<?, ?> device : entries(parameters.get("devices"))) {
            if (device.get("hostPath") instanceof String hostPath) {
                String containerPath = device.get("containerPath") instanceof String path && !path.isBlank()
                        ? path : hostPath;
                spec.withDevice(hostPath, containerPath, cgroupPermissions(strings(device.get("permissions"))));
            }
        }
        Long sharedMemorySize = number(parameters.get("sharedMemorySize"));
        if (sharedMemorySize != null) {
            spec.withShmSizeBytes(sharedMemorySize * MIB);
        }
        for (Map<?, ?> mount : entries(parameters.get("tmpfs"))) {
            Long size = number(mount.get("size"));
            if (mount.get("containerPath") instanceof String containerPath && size != null) {
                List<String> options = new ArrayList<>(strings(mount.get("mountOptions")));
                options.add("size=" + size + "m");
                spec.withTmpfs(containerPath, String.join(",", options));
            }
        }
        Long maxSwap = number(parameters.get("maxSwap"));
        if (maxSwap != null) {
            if (memoryLimitMb != null) {
                spec.withMemorySwapBytes((memoryLimitMb + maxSwap) * MIB);
            }
            // Docker takes a swappiness without a swap limit; registration keeps one only with maxSwap.
            Long swappiness = number(parameters.get("swappiness"));
            if (swappiness != null) {
                spec.withMemorySwappiness(swappiness);
            }
        }
    }

    private static void rejectOnFargate(boolean present, String parameter) {
        if (present) {
            throw new AwsException("ClientException",
                    "Fargate compatible task definitions do not support " + parameter, 400);
        }
    }

    /** ECS's {@code read}, {@code write} and {@code mknod} as Docker's cgroup permission letters. */
    private static String cgroupPermissions(List<String> permissions) {
        if (permissions.isEmpty()) {
            return "rwm";
        }
        StringBuilder letters = new StringBuilder();
        if (permissions.contains("read")) {
            letters.append('r');
        }
        if (permissions.contains("write")) {
            letters.append('w');
        }
        if (permissions.contains("mknod")) {
            letters.append('m');
        }
        return letters.toString();
    }

    private static Map<?, ?> of(ContainerDefinition def) {
        Object linuxParameters = def.getUnparsed() == null ? null : def.getUnparsed().get("linuxParameters");
        return linuxParameters instanceof Map<?, ?> parameters ? parameters : null;
    }

    private static List<String> capabilities(Map<?, ?> parameters, String list) {
        return parameters.get("capabilities") instanceof Map<?, ?> capabilities
                ? strings(capabilities.get(list)) : List.of();
    }

    /** The members of a list as written, each as text; empty when it is not a list. */
    private static List<String> strings(Object list) {
        List<String> result = new ArrayList<>();
        if (list instanceof Collection<?> items) {
            for (Object item : items) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private static List<Map<?, ?>> entries(Object list) {
        List<Map<?, ?>> entries = new ArrayList<>();
        if (list instanceof Collection<?> items) {
            for (Object item : items) {
                if (item instanceof Map<?, ?> entry) {
                    entries.add(entry);
                }
            }
        }
        return entries;
    }

    private static Long number(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }
}
