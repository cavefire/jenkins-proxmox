package org.jenkinsci.plugins.proxmox.provisioning;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHAuthenticator;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.trilead.ssh2.ChannelCondition;
import com.trilead.ssh2.Connection;
import com.trilead.ssh2.LocalPortForwarder;
import com.trilead.ssh2.ServerHostKeyVerifier;
import com.trilead.ssh2.Session;
import com.trilead.ssh2.StreamGobbler;
import hudson.model.TaskListener;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.apache.commons.io.IOUtils;

/**
 * SSH connection to a Proxmox host, used to reach its API and the clones on it. Only
 * port forwarding is used; no commands are run on the hops.
 */
public final class PveHostShell implements Closeable {

    private static final Logger LOGGER = Logger.getLogger(PveHostShell.class.getName());

    private final Connection connection;
    private final String host;

    private PveHostShell(Connection connection, String host) {
        this.connection = connection;
        this.host = host;
    }

    /**
     * @param expectedFingerprint OpenSSH style {@code SHA256:...} host key fingerprint; empty accepts any key
     */
    public static PveHostShell connect(
            String host,
            int port,
            StandardUsernameCredentials credentials,
            String expectedFingerprint,
            TaskListener listener)
            throws IOException, InterruptedException {
        Connection connection = new Connection(host, port);
        boolean ok = false;
        try {
            ServerHostKeyVerifier verifier = (hostname, p, algorithm, key) -> {
                String actual = fingerprint(key);
                if (expectedFingerprint == null || expectedFingerprint.trim().isEmpty()) {
                    LOGGER.log(Level.FINE, "Accepting host key {0} of {1}", new Object[] {actual, hostname});
                    return true;
                }
                if (!actual.equals(expectedFingerprint.trim())) {
                    listener.error("Host key of " + hostname + " is " + actual + ", expected " + expectedFingerprint);
                    return false;
                }
                return true;
            };
            connection.connect(verifier, 30_000, 30_000);
            if (!SSHAuthenticator.newInstance(connection, credentials).authenticate(listener)) {
                throw new IOException(
                        "SSH authentication to " + host + " as " + credentials.getUsername() + " failed");
            }
            ok = true;
            return new PveHostShell(connection, host);
        } finally {
            if (!ok) {
                connection.close();
            }
        }
    }

    static String fingerprint(byte[] key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(key);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public String getHost() {
        return host;
    }

    /** @return false once the connection has been closed or has failed */
    public boolean isAlive() {
        try {
            connection.sendIgnorePacket();
            return true;
        } catch (IOException | IllegalStateException e) {
            return false;
        }
    }

    /** A local port forwarded through the SSH connection. */
    public static final class Forward implements Closeable {
        private final LocalPortForwarder forwarder;
        private final int localPort;

        Forward(LocalPortForwarder forwarder, int localPort) {
            this.forwarder = forwarder;
            this.localPort = localPort;
        }

        public int getLocalPort() {
            return localPort;
        }

        @Override
        public void close() {
            try {
                forwarder.close();
            } catch (IOException e) {
                LOGGER.log(Level.FINE, "Closing port forwarding failed", e);
            }
        }
    }

    /** Forwards a free port on the loopback interface to {@code targetHost:targetPort} as seen by the remote host. */
    public Forward forward(String targetHost, int targetPort) throws IOException {
        int localPort;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            localPort = probe.getLocalPort();
        }
        LocalPortForwarder forwarder = connection.createLocalPortForwarder(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), localPort), targetHost, targetPort);
        return new Forward(forwarder, localPort);
    }

    /** Result of a command that ran to completion. */
    public static final class Result {
        public final int exitCode;
        public final String stdout;
        public final String stderr;

        Result(int exitCode, String stdout, String stderr) {
            this.exitCode = exitCode;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        public boolean ok() {
            return exitCode == 0;
        }
    }

    /** Runs a command with small output to completion. */
    public Result run(String command, long timeoutMillis) throws IOException, InterruptedException {
        Session session = connection.openSession();
        try {
            session.execCommand(command);
            session.getStdin().close();
            try (StreamGobbler out = new StreamGobbler(session.getStdout());
                    StreamGobbler err = new StreamGobbler(session.getStderr())) {
                int condition = session.waitForCondition(ChannelCondition.EXIT_STATUS, timeoutMillis);
                if ((condition & ChannelCondition.TIMEOUT) != 0) {
                    throw new IOException("Timed out running '" + command + "'");
                }
                Integer exit = session.getExitStatus();
                return new Result(
                        exit == null ? -1 : exit,
                        new String(IOUtils.toByteArray(out), StandardCharsets.UTF_8),
                        new String(IOUtils.toByteArray(err), StandardCharsets.UTF_8));
            }
        } finally {
            session.close();
        }
    }

    @Override
    public void close() {
        connection.close();
    }
}
