package org.jenkinsci.plugins.proxmox.provisioning;

import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import hudson.model.TaskListener;
import java.io.Closeable;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkinsci.plugins.proxmox.pve2api.Connector;

/**
 * SSH connections to the Proxmox hosts, for networks where only port 22 of the hosts is reachable. The Proxmox API
 * of a host is tunneled to its local port, and clones are reached through the host running them. One connection per
 * host is shared and reopened when it breaks.
 */
public final class ProxmoxSsh implements Connector.Tunnel, Closeable {

    private static final Logger LOGGER = Logger.getLogger(ProxmoxSsh.class.getName());

    private final int port;
    private final Supplier<StandardUsernameCredentials> credentials;

    private final Map<String, PveHostShell> connections = new HashMap<>();
    /** Long-lived forwards to the API, by "host:port". */
    private final Map<String, PveHostShell.Forward> routes = new HashMap<>();

    public ProxmoxSsh(int port, Supplier<StandardUsernameCredentials> credentials) {
        this.port = port;
        this.credentials = credentials;
    }

    /** The open connection to a Proxmox host, reconnecting if it was lost. */
    public synchronized PveHostShell connection(String host, TaskListener listener) throws IOException {
        PveHostShell c = connections.get(host);
        if (c != null && c.isAlive()) {
            return c;
        }
        if (c != null) {
            LOGGER.log(Level.INFO, "SSH connection to {0} lost, reconnecting", host);
            drop(host);
        }
        StandardUsernameCredentials creds = credentials.get();
        if (creds == null) {
            throw new IOException("SSH credentials for the Proxmox hosts not found");
        }
        try {
            c = PveHostShell.connect(host, port, creds, null, listener);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while connecting to " + host, e);
        }
        connections.put(host, c);
        return c;
    }

    /** Reaches {@code port} of a Proxmox host (e.g. the API) through that host's own SSH server. */
    @Override
    public synchronized InetSocketAddress route(String host, int targetPort) throws IOException {
        PveHostShell c = connection(host, TaskListener.NULL);
        String key = host + ":" + targetPort;
        PveHostShell.Forward forward = routes.get(key);
        if (forward == null) {
            forward = c.forward("127.0.0.1", targetPort);
            routes.put(key, forward);
        }
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), forward.getLocalPort());
    }

    /** A new forward through {@code host} to {@code targetHost:targetPort}; the caller closes it. */
    public synchronized PveHostShell.Forward open(String host, String targetHost, int targetPort, TaskListener listener)
            throws IOException {
        return connection(host, listener).forward(targetHost, targetPort);
    }

    private void drop(String host) {
        routes.entrySet().removeIf(e -> {
            if (e.getKey().startsWith(host + ":")) {
                e.getValue().close();
                return true;
            }
            return false;
        });
        PveHostShell c = connections.remove(host);
        if (c != null) {
            c.close();
        }
    }

    @Override
    public synchronized void close() {
        for (String host : connections.keySet().toArray(new String[0])) {
            drop(host);
        }
    }
}
