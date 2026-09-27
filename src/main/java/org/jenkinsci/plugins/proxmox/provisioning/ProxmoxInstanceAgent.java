package org.jenkinsci.plugins.proxmox.provisioning;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Descriptor;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.slaves.AbstractCloudComputer;
import hudson.slaves.AbstractCloudSlave;
import hudson.slaves.Cloud;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.Jenkins;
import kong.unirest.json.JSONObject;
import org.jenkinsci.plugins.durabletask.executors.OnceRetentionStrategy;
import org.jenkinsci.plugins.proxmox.Datacenter;
import org.jenkinsci.plugins.proxmox.pve2api.Connector;
import org.jenkinsci.plugins.proxmox.pve2api.ProxmoxException;

/**
 * Single-use agent running in a clone of a {@link ProxmoxTemplate}. The clone is deleted when the agent is
 * terminated, which happens after its one build, but only if the clone's notes still name that build.
 */
public class ProxmoxInstanceAgent extends AbstractCloudSlave {

    private static final long serialVersionUID = 1L;
    private static final Logger LOGGER = Logger.getLogger(ProxmoxInstanceAgent.class.getName());

    private final String datacenterDescription;
    private final String pveNode;
    private final String guestType;
    private final int vmid;
    private final int templateVmid;
    private final String instanceId;
    private final String nodeUrl;
    /** Mapped resources ({@link GuestRequirements#getMappings()}), memory and cores of the clone. */
    private final List<String> mappings;
    private final long memoryBytes;
    private final int cores;

    @CheckForNull
    private volatile String buildUrl;

    public ProxmoxInstanceAgent(
            String name,
            String datacenterDescription,
            ProxmoxTemplate template,
            String pveNode,
            GuestRequirements requirements,
            int vmid,
            String instanceId,
            String nodeUrl,
            TemplateSettings settings,
            int idleMinutes)
            throws Descriptor.FormException, IOException {
        super(name, settings.getRemoteFS(), new ProxmoxSshLauncher(settings));
        this.datacenterDescription = datacenterDescription;
        this.pveNode = pveNode;
        this.mappings = new ArrayList<>(requirements.getMappings());
        this.memoryBytes = requirements.getMemoryBytes();
        this.cores = requirements.getCores();
        this.guestType = template.getType();
        this.vmid = vmid;
        this.templateVmid = template.getVmid();
        this.instanceId = instanceId;
        this.nodeUrl = nodeUrl;
        setNodeDescription("Clone " + vmid + " of Proxmox template " + template.getName() + " on " + pveNode);
        setNumExecutors(1);
        setMode(Mode.EXCLUSIVE);
        setLabelString(template.getLabelString());
        setRetentionStrategy(new OnceRetentionStrategy(idleMinutes));
    }

    /** URL Jenkins writes into the clone's notes to identify this node. */
    public static String nodeUrl(String nodeName) {
        String root = Jenkins.get().getRootUrl();
        return (root == null ? "/" : root) + "computer/" + Util.rawEncode(nodeName) + "/";
    }

    /** URL Jenkins writes into the clone's notes to identify the build using it. */
    public static String buildUrl(Run<?, ?> run) {
        String root = Jenkins.get().getRootUrl();
        return (root == null ? "/" : root) + run.getUrl();
    }

    @CheckForNull
    public Datacenter getDatacenter() {
        for (Cloud cloud : Jenkins.get().clouds) {
            if (cloud instanceof Datacenter
                    && ((Datacenter) cloud).getDatacenterDescription().equals(datacenterDescription)) {
                return (Datacenter) cloud;
            }
        }
        return null;
    }

    @NonNull
    Datacenter requireDatacenter() throws IOException {
        Datacenter dc = getDatacenter();
        if (dc == null) {
            throw new IOException("Proxmox datacenter '" + datacenterDescription + "' is no longer configured");
        }
        return dc;
    }

    public String getPveNode() {
        return pveNode;
    }

    /** Resource usage of the clone, for placing further clones. */
    public GuestRequirements getRequirements() {
        return new GuestRequirements(
                new LinkedHashSet<>(mappings == null ? Collections.<String>emptyList() : mappings),
                false,
                Collections.<String>emptySet(),
                memoryBytes,
                cores);
    }

    public String getGuestType() {
        return guestType;
    }

    public int getVmid() {
        return vmid;
    }

    public int getTemplateVmid() {
        return templateVmid;
    }

    public String getInstanceId() {
        return instanceId;
    }

    public String getNodeUrl() {
        return nodeUrl;
    }

    @CheckForNull
    public String getBuildUrl() {
        return buildUrl;
    }

    /** Writes the build using this agent into the clone's notes. */
    void recordBuild(Run<?, ?> run) {
        String url = buildUrl(run);
        try {
            Datacenter dc = requireDatacenter();
            dc.proxmoxInstance()
                    .updateGuestConfig(
                            pveNode,
                            guestType,
                            vmid,
                            Connector.params("description", InstanceNotes.render(instanceId, nodeUrl, url)));
            buildUrl = url;
            Jenkins.get().updateNode(this);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not record build " + url + " in the notes of Proxmox guest " + vmid, e);
        }
    }

    @Override
    public AbstractCloudComputer<ProxmoxInstanceAgent> createComputer() {
        return new ProxmoxInstanceComputer(this);
    }

    @Override
    protected void _terminate(TaskListener listener) throws IOException, InterruptedException {
        Datacenter dc = getDatacenter();
        if (dc == null) {
            listener.error("Proxmox datacenter '%s' is gone, leaving guest %d in place", datacenterDescription, vmid);
            return;
        }
        destroyIfOwned(dc.proxmoxInstance(), pveNode, guestType, vmid, instanceId, nodeUrl, buildUrl, listener);
    }

    /**
     * Stops and deletes a clone, but only if it is a Jenkins instance (not a template) whose notes name this
     * Jenkins, the given node and the given build.
     *
     * @return true if the clone was deleted
     */
    public static boolean destroyIfOwned(
            Connector pve,
            String pveNode,
            String type,
            int vmid,
            String instanceId,
            String nodeUrl,
            @CheckForNull String buildUrl,
            TaskListener listener)
            throws IOException, InterruptedException {
        JSONObject config;
        try {
            config = pve.getGuestConfig(pveNode, type, vmid);
        } catch (ProxmoxException e) {
            listener.getLogger().println("Proxmox guest " + vmid + " not found, nothing to delete: " + e.getMessage());
            return false;
        }
        if (config.optInt("template", 0) == 1
                || !ProxmoxTemplate.parseTags(config.optString("tags", "")).contains(ProxmoxTemplate.INSTANCE_TAG)) {
            listener.error(
                    "Refusing to delete Proxmox guest %d: it is not tagged %s", vmid, ProxmoxTemplate.INSTANCE_TAG);
            return false;
        }
        String blocker =
                InstanceNotes.deletionBlocker(config.optString("description", null), instanceId, nodeUrl, buildUrl);
        if (blocker != null) {
            listener.error("Refusing to delete Proxmox guest %d: %s", vmid, blocker);
            return false;
        }
        long timeout = 5 * 60 * 1000L;
        if (!"stopped".equals(pve.getGuestStatus(pveNode, type, vmid).optString("status"))) {
            listener.getLogger().println("Stopping Proxmox guest " + vmid);
            pve.waitForTaskOk(pveNode, pve.stopGuest(pveNode, type, vmid), timeout);
        }
        listener.getLogger().println("Deleting Proxmox guest " + vmid);
        pve.waitForTaskOk(pveNode, pve.destroyGuest(pveNode, type, vmid), timeout);
        return true;
    }

    @Extension
    public static final class DescriptorImpl extends SlaveDescriptor {
        @NonNull
        @Override
        public String getDisplayName() {
            return "Proxmox template instance";
        }

        @Override
        public boolean isInstantiable() {
            return false;
        }
    }
}
