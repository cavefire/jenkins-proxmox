package org.jenkinsci.plugins.proxmox.provisioning;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.List;
import java.util.Map;

/**
 * How Jenkins connects to clones of a template, read from {@code jenkins-*} lines in the template's Proxmox notes,
 * with defaults from the cloud configuration. Example notes of a Windows template:
 *
 * <pre>
 * jenkins-credentials: windows-ssh
 * jenkins-remote-fs: C:\jenkins
 * jenkins-java: C:\Program Files\Eclipse Adoptium\jdk-11\bin\java.exe
 * </pre>
 */
public final class TemplateSettings {

    public static final String CREDENTIALS = "jenkins-credentials";
    public static final String REMOTE_FS = "jenkins-remote-fs";
    public static final String JAVA = "jenkins-java";
    public static final String JVM_OPTIONS = "jenkins-jvm-options";
    public static final String SSH_PORT = "jenkins-ssh-port";
    public static final String NETWORK = "jenkins-network";

    private final String credentialsId;
    private final String remoteFS;
    private final String javaPath;
    private final String jvmOptions;
    private final int sshPort;
    private final String network;

    public TemplateSettings(
            String credentialsId, String remoteFS, String javaPath, String jvmOptions, int sshPort, String network) {
        this.credentialsId = credentialsId;
        this.remoteFS = remoteFS;
        this.javaPath = javaPath;
        this.jvmOptions = jvmOptions;
        this.sshPort = sshPort;
        this.network = network;
    }

    /**
     * @param notes the template's notes
     * @param defaults settings used for keys missing from the notes
     */
    public static TemplateSettings parse(@CheckForNull String notes, TemplateSettings defaults) {
        Map<String, String> values = InstanceNotes.parse(notes);
        int port = defaults.sshPort;
        if (values.containsKey(SSH_PORT)) {
            try {
                port = Integer.parseInt(values.get(SSH_PORT));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(SSH_PORT + " is not a number: " + values.get(SSH_PORT), e);
            }
        }
        String network = values.getOrDefault(NETWORK, defaults.network);
        if (network != null && Ipv4Network.parse(network) == null) {
            throw new IllegalArgumentException(NETWORK + " is not an IPv4 network like 10.10.10.0/24: " + network);
        }
        return new TemplateSettings(
                values.getOrDefault(CREDENTIALS, defaults.credentialsId),
                values.getOrDefault(REMOTE_FS, defaults.remoteFS),
                values.getOrDefault(JAVA, defaults.javaPath),
                values.getOrDefault(JVM_OPTIONS, defaults.jvmOptions),
                port,
                network);
    }

    /** Picks the address to connect to: the first one, or the first one in {@link #getNetwork()} if set. */
    @CheckForNull
    public String pickAddress(List<String> addresses) {
        Ipv4Network net = network == null ? null : Ipv4Network.parse(network);
        for (String address : addresses) {
            if (net == null || net.contains(address)) {
                return address;
            }
        }
        return null;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public String getRemoteFS() {
        return remoteFS;
    }

    @CheckForNull
    public String getJavaPath() {
        return javaPath;
    }

    @CheckForNull
    public String getJvmOptions() {
        return jvmOptions;
    }

    public int getSshPort() {
        return sshPort;
    }

    @CheckForNull
    public String getNetwork() {
        return network;
    }

    @Override
    public String toString() {
        return "credentials " + credentialsId + ", agent directory " + remoteFS + ", SSH port " + sshPort
                + (javaPath == null ? "" : ", java " + javaPath)
                + (network == null ? "" : ", network " + network);
    }

    /** An IPv4 network in CIDR notation. */
    static final class Ipv4Network {
        private final int address;
        private final int mask;

        private Ipv4Network(int address, int mask) {
            this.address = address;
            this.mask = mask;
        }

        @CheckForNull
        static Ipv4Network parse(String cidr) {
            String[] parts = cidr.trim().split("/");
            Integer address = toInt(parts[0]);
            if (address == null || parts.length > 2) {
                return null;
            }
            int bits;
            try {
                bits = parts.length == 2 ? Integer.parseInt(parts[1]) : 32;
            } catch (NumberFormatException e) {
                return null;
            }
            if (bits < 0 || bits > 32) {
                return null;
            }
            int mask = bits == 0 ? 0 : -1 << (32 - bits);
            return new Ipv4Network(address & mask, mask);
        }

        boolean contains(String ip) {
            Integer value = toInt(ip);
            return value != null && (value & mask) == address;
        }

        @CheckForNull
        static Integer toInt(String ip) {
            String[] octets = ip.trim().split("\\.");
            if (octets.length != 4) {
                return null;
            }
            int value = 0;
            for (String octet : octets) {
                int v;
                try {
                    v = Integer.parseInt(octet);
                } catch (NumberFormatException e) {
                    return null;
                }
                if (v < 0 || v > 255) {
                    return null;
                }
                value = value << 8 | v;
            }
            return value;
        }
    }
}
