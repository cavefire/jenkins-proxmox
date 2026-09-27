package org.jenkinsci.plugins.proxmox.provisioning;

import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;
import kong.unirest.json.JSONObject;

/**
 * What a guest needs from the Proxmox node it runs on, read from its configuration: mapped PCI, USB and directory
 * resources, storages, memory and cores. Devices passed through without a mapping (e.g. {@code hostpci0: 0000:01:00}
 * or {@code usb0: host=1234:5678}) only exist on the node the guest was created on, so they pin the guest there.
 */
public final class GuestRequirements {

    private static final Pattern QEMU_DISK = Pattern.compile("(ide|sata|scsi|virtio|efidisk|tpmstate)\\d+");
    private static final Pattern LXC_DISK = Pattern.compile("rootfs|mp\\d+");

    private final Set<String> mappings;
    private final boolean pinned;
    private final Set<String> storages;
    private final long memoryBytes;
    private final int cores;

    public GuestRequirements(Set<String> mappings, boolean pinned, Set<String> storages, long memoryBytes, int cores) {
        this.mappings = Collections.unmodifiableSet(new LinkedHashSet<>(mappings));
        this.pinned = pinned;
        this.storages = Collections.unmodifiableSet(new LinkedHashSet<>(storages));
        this.memoryBytes = memoryBytes;
        this.cores = cores;
    }

    /** Mapping key as used in {@link #getMappings()}, e.g. {@code pci:gpu}. */
    public static String mappingKey(String kind, String id) {
        return kind + ":" + id;
    }

    public static GuestRequirements fromConfig(String type, JSONObject config) {
        Set<String> mappings = new LinkedHashSet<>();
        Set<String> storages = new LinkedHashSet<>();
        boolean pinned = false;
        boolean qemu = "qemu".equals(type);
        for (Iterator<String> it = config.keys(); it.hasNext(); ) {
            String key = it.next();
            String value = String.valueOf(config.get(key));
            if (qemu && key.matches("hostpci\\d+")) {
                String mapping = option(value, "mapping");
                if (mapping != null) {
                    mappings.add(mappingKey("pci", mapping));
                } else {
                    pinned = true;
                }
            } else if (qemu && key.matches("usb\\d+")) {
                String mapping = option(value, "mapping");
                if (mapping != null) {
                    mappings.add(mappingKey("usb", mapping));
                } else if (option(value, "host") != null) {
                    pinned = true;
                }
            } else if (qemu && key.matches("virtiofs\\d+")) {
                String dir = option(value, "dirid");
                if (dir == null) {
                    dir = value.split(",")[0];
                }
                mappings.add(mappingKey("dir", dir));
            } else if ((qemu ? QEMU_DISK : LXC_DISK).matcher(key).matches()) {
                String storage = storageOf(value);
                if (storage != null) {
                    storages.add(storage);
                }
            }
        }
        long memoryMiB = parseLong(config.optString("memory", ""), 512);
        int cores = (int) parseLong(config.optString("cores", ""), 1);
        if (qemu) {
            cores *= (int) parseLong(config.optString("sockets", ""), 1);
        }
        return new GuestRequirements(mappings, pinned, storages, memoryMiB * 1024 * 1024, cores);
    }

    /** Value of {@code key=...} in a Proxmox property string, or null. */
    public static String option(String propertyString, String key) {
        for (String part : propertyString.split(",")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).trim().equals(key)) {
                return part.substring(eq + 1).trim();
            }
        }
        return null;
    }

    /** Storage of a disk entry like {@code local-lvm:vm-100-disk-0,size=4M}; null for CD-ROMs and host paths. */
    static String storageOf(String diskValue) {
        if ("cdrom".equals(option(diskValue, "media"))) {
            return null;
        }
        String volume = diskValue.split(",")[0].trim();
        if (volume.startsWith("volume=")) {
            volume = volume.substring("volume=".length());
        }
        int colon = volume.indexOf(':');
        if (colon <= 0 || volume.startsWith("/")) {
            return null;
        }
        return volume.substring(0, colon);
    }

    private static long parseLong(String s, long fallback) {
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public Set<String> getMappings() {
        return mappings;
    }

    /** True if the guest uses devices without a mapping and can only run on its own node. */
    public boolean isPinned() {
        return pinned;
    }

    public Set<String> getStorages() {
        return storages;
    }

    public long getMemoryBytes() {
        return memoryBytes;
    }

    public int getCores() {
        return cores;
    }

    @Override
    public String toString() {
        return (mappings.isEmpty() ? "no mapped devices" : "mapped " + String.join(", ", mappings))
                + (pinned ? ", unmapped devices (pinned to its node)" : "")
                + ", storage " + String.join(", ", storages);
    }
}
