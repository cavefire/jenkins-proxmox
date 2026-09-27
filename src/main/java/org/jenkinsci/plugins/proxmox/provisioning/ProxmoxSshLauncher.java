package org.jenkinsci.plugins.proxmox.provisioning;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.plugins.sshslaves.SSHLauncher;
import hudson.plugins.sshslaves.verifiers.NonVerifyingKeyVerificationStrategy;
import hudson.slaves.ComputerLauncher;
import hudson.slaves.SlaveComputer;
import java.io.IOException;
import org.jenkinsci.plugins.proxmox.Datacenter;
import org.jenkinsci.plugins.proxmox.pve2api.Connector;

/**
 * Starts a clone, waits until Proxmox reports its IP address and connects to its SSH server with the standard
 * {@link SSHLauncher}. With SSH credentials for the Proxmox hosts, the connection goes through the host running the
 * clone ({@link Datacenter#routeToGuest}), so the clone can sit on a network only that host reaches.
 */
public class ProxmoxSshLauncher extends ComputerLauncher {

    private final String credentialsId;
    private final int sshPort;

    @CheckForNull
    private final String javaPath;

    @CheckForNull
    private final String jvmOptions;

    @CheckForNull
    private final String network;

    private transient volatile SSHLauncher delegate;
    private transient volatile Datacenter.Route route;

    public ProxmoxSshLauncher(TemplateSettings settings) {
        this.credentialsId = settings.getCredentialsId();
        this.sshPort = settings.getSshPort();
        this.javaPath = settings.getJavaPath();
        this.jvmOptions = settings.getJvmOptions();
        this.network = settings.getNetwork();
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public int getSshPort() {
        return sshPort;
    }

    @Override
    public boolean isLaunchSupported() {
        return true;
    }

    @Override
    public void launch(SlaveComputer computer, TaskListener listener) throws IOException, InterruptedException {
        Node n = computer.getNode();
        if (!(n instanceof ProxmoxInstanceAgent)) {
            throw new IOException("Unexpected node for " + computer.getName());
        }
        ProxmoxInstanceAgent agent = (ProxmoxInstanceAgent) n;
        Datacenter dc = agent.requireDatacenter();
        Connector pve = dc.proxmoxInstance();
        String node = agent.getPveNode();
        String type = agent.getGuestType();
        int vmid = agent.getVmid();
        long deadline = System.currentTimeMillis() + dc.getStartupTimeoutSeconds() * 1000L;

        if (!"running".equals(pve.getGuestStatus(node, type, vmid).optString("status"))) {
            listener.getLogger().println("Starting Proxmox guest " + vmid);
            pve.waitForTaskOk(node, pve.startGuest(node, type, vmid), remaining(deadline));
        }

        listener.getLogger().println("Waiting for the IP address of guest " + vmid
                + ("qemu".equals(type) ? " (reported by the QEMU guest agent)" : ""));
        TemplateSettings settings = new TemplateSettings(credentialsId, agent.getRemoteFS(), javaPath, jvmOptions, sshPort, network);
        String address;
        while ((address = settings.pickAddress(pve.getGuestAddresses(node, type, vmid))) == null) {
            Thread.sleep(Math.min(3000, remaining(deadline)));
        }
        listener.getLogger().println("Guest " + vmid + " has address " + address);

        closeTunnel();
        Datacenter.Route r = dc.routeToGuest(node, address, sshPort, listener);
        route = r;
        listener.getLogger().println("Connecting " + r.description);
        String host = r.host;
        int port = r.port;

        int timeoutSeconds = (int) Math.max(1, remaining(deadline) / 1000);
        SSHLauncher ssh = new SSHLauncher(host, port, credentialsId);
        ssh.setJavaPath(javaPath);
        ssh.setJvmOptions(jvmOptions);
        ssh.setSshHostKeyVerificationStrategy(new NonVerifyingKeyVerificationStrategy());
        // The SSH server may come up after the network, so keep retrying until the startup timeout.
        ssh.setLaunchTimeoutSeconds(Math.min(60, timeoutSeconds));
        ssh.setRetryWaitTime(10);
        ssh.setMaxNumRetries(Math.max(1, timeoutSeconds / 10));
        delegate = ssh;
        ssh.launch(computer, listener);
        if (computer.getChannel() == null) {
            closeTunnel();
        }
    }

    private static long remaining(long deadline) throws IOException {
        long left = deadline - System.currentTimeMillis();
        if (left <= 0) {
            throw new IOException("Startup timeout exceeded");
        }
        return left;
    }

    private void closeTunnel() {
        Datacenter.Route r = route;
        route = null;
        if (r != null) {
            r.close();
        }
    }

    @Override
    public void beforeDisconnect(SlaveComputer computer, TaskListener listener) {
        SSHLauncher d = delegate;
        if (d != null) {
            d.beforeDisconnect(computer, listener);
        }
        super.beforeDisconnect(computer, listener);
    }

    @Override
    public void afterDisconnect(SlaveComputer computer, TaskListener listener) {
        SSHLauncher d = delegate;
        if (d != null) {
            d.afterDisconnect(computer, listener);
        }
        closeTunnel();
        super.afterDisconnect(computer, listener);
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<ComputerLauncher> {
        @NonNull
        @Override
        public String getDisplayName() {
            return "SSH to a Proxmox template instance";
        }
    }
}
