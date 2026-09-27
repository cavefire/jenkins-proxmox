package org.jenkinsci.plugins.proxmox;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHAuthenticator;
import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernameCredentials;
import com.cloudbees.plugins.credentials.common.StandardUsernameListBoxModel;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import com.trilead.ssh2.Connection;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.Util;
import hudson.model.Computer;
import hudson.model.Descriptor;
import hudson.model.Label;
import hudson.model.Node;
import hudson.model.TaskListener;
import hudson.security.ACL;
import hudson.slaves.Cloud;
import hudson.slaves.NodeProvisioner;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import hudson.util.LogTaskListener;
import hudson.util.Secret;
import java.io.Closeable;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.security.auth.login.LoginException;
import jenkins.model.Jenkins;
import kong.unirest.json.JSONArray;
import kong.unirest.json.JSONObject;
import org.jenkinsci.plugins.proxmox.provisioning.GuestRequirements;
import org.jenkinsci.plugins.proxmox.provisioning.InstanceNotes;
import org.jenkinsci.plugins.proxmox.provisioning.ProxmoxSsh;
import org.jenkinsci.plugins.proxmox.provisioning.Placement;
import org.jenkinsci.plugins.proxmox.provisioning.ProxmoxInstanceAgent;
import org.jenkinsci.plugins.proxmox.provisioning.ProxmoxTemplate;
import org.jenkinsci.plugins.proxmox.provisioning.PveHostShell;
import org.jenkinsci.plugins.proxmox.provisioning.TemplateSettings;
import org.jenkinsci.plugins.proxmox.pve2api.Connector;
import org.jenkinsci.plugins.proxmox.pve2api.ProxmoxException;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.StaplerRequest;
import org.kohsuke.stapler.verb.POST;

/**
 * Represents a Proxmox datacenter.
 */
public class Datacenter extends Cloud {

    private static final Logger LOGGER = Logger.getLogger(Datacenter.class.getName());

    private final String hostname;
    private final String username;
    private final String realm;
    private final Secret password;
    private final Boolean ignoreSSL;
    private transient Connector pveConnector;

    // Provisioning of single-use agents from templates tagged "jenkins-template"
    private boolean provisionFromTemplates;
    private String guestCredentialsId;
    private String agentRemoteFS;
    private String javaPath;
    /**
     * SSH login on the Proxmox hosts. When set, all connections go over SSH (port 22): the API is tunneled to the
     * hosts' local port, and clones are reached through the host running them.
     */
    private String sshCredentialsId;
    private int sshPort = DEFAULT_SSH_PORT;
    private int instanceCap = DEFAULT_INSTANCE_CAP;
    private boolean fullClone;
    private String cloneStorage;
    private int startupTimeoutSeconds = DEFAULT_STARTUP_TIMEOUT;
    /** Lowest VM ID for clones; 0 uses the next ID Proxmox suggests. */
    private int firstVmid;

    static final int DEFAULT_SSH_PORT = 22;
    static final int DEFAULT_INSTANCE_CAP = 10;
    static final int DEFAULT_STARTUP_TIMEOUT = 600;
    static final String DEFAULT_REMOTE_FS = "/home/jenkins/agent";
    private static final long TEMPLATE_CACHE_MILLIS = 60_000;

    private transient List<ProxmoxTemplate> templateCache;
    private transient long templateCacheTime;
    private transient int instancesInFlight;
    private transient Object placementLock = new Object();
    private transient List<Reservation> reservations = new ArrayList<>();
    /** VM IDs chosen for clones that are being created, so parallel clones do not pick the same one. */
    private transient Set<Integer> claimedVmids = new HashSet<>();
    private transient ProxmoxSsh ssh;
    private transient Map<String, String> nodeHosts;
    private transient long nodeHostsTime;

    /** Clone and migration tasks copy whole disks, so they get more time than the agent startup. */
    private static final long TASK_TIMEOUT_MILLIS = 60 * 60 * 1000L;

    @DataBoundConstructor
    public Datacenter(String hostname, String username, String realm, Secret password, Boolean ignoreSSL) {
        super("Datacenter(proxmox)");
        this.hostname = hostname;
        this.username = username;
        this.realm = realm;
        this.password = password;
        this.ignoreSSL = ignoreSSL;
        this.pveConnector = null;
    }

    private Object readResolve() {
        if (reservations == null) {
            reservations = new ArrayList<>();
        }
        if (placementLock == null) {
            placementLock = new Object();
        }
        if (claimedVmids == null) {
            claimedVmids = new HashSet<>();
        }
        // Configurations saved before template provisioning existed
        if (sshPort == 0) {
            sshPort = DEFAULT_SSH_PORT;
        }
        if (instanceCap == 0) {
            instanceCap = DEFAULT_INSTANCE_CAP;
        }
        if (startupTimeoutSeconds == 0) {
            startupTimeoutSeconds = DEFAULT_STARTUP_TIMEOUT;
        }
        return this;
    }

    @Override
    public boolean canProvision(CloudState state) {
        return provisionFromTemplates && findTemplate(state.getLabel()) != null;
    }

    @Override
    public Collection<NodeProvisioner.PlannedNode> provision(CloudState state, int excessWorkload) {
        Label label = state.getLabel();
        ProxmoxTemplate template = provisionFromTemplates ? findTemplate(label) : null;
        if (template == null) {
            return Collections.emptySet();
        }
        List<NodeProvisioner.PlannedNode> planned = new ArrayList<>();
        synchronized (this) {
            int free = instanceCap - countInstances() - instancesInFlight;
            for (int i = 0; i < Math.min(excessWorkload, free); i++) {
                instancesInFlight++;
                planned.add(new NodeProvisioner.PlannedNode(
                        template.getName(),
                        Computer.threadPoolForRemoting.submit(() -> {
                            try {
                                return createInstance(label);
                            } finally {
                                synchronized (Datacenter.this) {
                                    instancesInFlight--;
                                }
                            }
                        }),
                        1));
            }
        }
        if (planned.isEmpty()) {
            LOGGER.log(Level.INFO, "Instance cap of {0} reached for {1}", new Object[] {instanceCap, hostname});
        }
        return planned;
    }

    private int countInstances() {
        int count = 0;
        for (Node node : Jenkins.get().getNodes()) {
            if (node instanceof ProxmoxInstanceAgent && this == ((ProxmoxInstanceAgent) node).getDatacenter()) {
                count++;
            }
        }
        return count;
    }

    @CheckForNull
    ProxmoxTemplate findTemplate(@CheckForNull Label label) {
        if (label == null) {
            return null;
        }
        try {
            for (ProxmoxTemplate template : getTemplates()) {
                if (template.matches(label)) {
                    return template;
                }
            }
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Could not list Proxmox templates on " + hostname, e);
        }
        return null;
    }

    /** Templates tagged {@value ProxmoxTemplate#TEMPLATE_TAG}, cached for a minute. */
    public synchronized List<ProxmoxTemplate> getTemplates() throws IOException {
        if (templateCache == null || System.currentTimeMillis() - templateCacheTime > TEMPLATE_CACHE_MILLIS) {
            // Also remember failures, so an unreachable Proxmox does not slow down every provisioning check.
            templateCacheTime = System.currentTimeMillis();
            templateCache = Collections.emptyList();
            templateCache = readTemplates(proxmoxInstance());
        }
        return templateCache;
    }

    static List<ProxmoxTemplate> readTemplates(Connector pve) throws IOException {
        List<ProxmoxTemplate> templates = new ArrayList<>();
        JSONArray guests = pve.getClusterGuests();
        for (int i = 0; i < guests.length(); i++) {
            ProxmoxTemplate template = ProxmoxTemplate.fromResource(guests.getJSONObject(i));
            if (template != null) {
                templates.add(template);
            }
        }
        templates.sort(Comparator.comparingInt(ProxmoxTemplate::getVmid));
        return templates;
    }

    /** Templates matching a label, with what they need from a node. */
    List<Placement.Candidate> candidates(Connector pve, Label label) throws IOException {
        List<Placement.Candidate> candidates = new ArrayList<>();
        for (ProxmoxTemplate template : readTemplates(pve)) {
            if (template.matches(label)) {
                JSONObject config = pve.getGuestConfig(template.getNode(), template.getType(), template.getVmid());
                candidates.add(new Placement.Candidate(
                        template, GuestRequirements.fromConfig(template.getType(), config)));
            }
        }
        return candidates;
    }

    /**
     * Current state of the online cluster nodes: load, storages, resource mappings and their use by running guests,
     * this cloud's agents and clones being created.
     *
     * @param countDeviceUsage whether to read the configuration of running guests to count mapped devices in use
     */
    Placement readPlacement(Connector pve, boolean countDeviceUsage) throws IOException {
        List<Placement.NodeState> states = new ArrayList<>();
        JSONArray nodes = pve.getClusterNodes();
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject n = nodes.getJSONObject(i);
            if (!"online".equals(n.optString("status"))) {
                continue;
            }
            String name = n.getString("node");
            Map<String, Boolean> storages = new HashMap<>();
            try {
                JSONArray list = pve.getNodeStorages(name);
                for (int j = 0; j < list.length(); j++) {
                    JSONObject st = list.getJSONObject(j);
                    if (st.optInt("active", 1) == 1) {
                        storages.put(st.getString("storage"), st.optInt("shared", 0) == 1);
                    }
                }
            } catch (ProxmoxException e) {
                LOGGER.log(Level.WARNING, "Could not read the storages of node " + name, e);
                continue;
            }
            states.add(new Placement.NodeState(
                    name, n.optDouble("cpu", 0), n.optInt("maxcpu", 1), n.optLong("mem", 0), n.optLong("maxmem", 1), storages));
        }

        Map<String, Placement.Mapping> mappings = new HashMap<>();
        for (String kind : new String[] {"pci", "usb", "dir"}) {
            JSONArray list = pve.getResourceMappings(kind);
            for (int i = 0; i < list.length(); i++) {
                JSONObject mapping = list.getJSONObject(i);
                Map<String, Integer> perNode = new HashMap<>();
                JSONArray map = mapping.optJSONArray("map");
                for (int j = 0; map != null && j < map.length(); j++) {
                    String node = GuestRequirements.option(map.getString(j), "node");
                    if (node != null) {
                        perNode.merge(node, 1, Integer::sum);
                    }
                }
                boolean shareable = "dir".equals(kind) || "1".equals(String.valueOf(mapping.opt("mdev")));
                mappings.put(
                        GuestRequirements.mappingKey(kind, mapping.getString("id")),
                        new Placement.Mapping(perNode, shareable));
            }
        }

        Placement placement = new Placement(states, mappings);
        Set<Integer> ours = new HashSet<>();
        for (Node node : Jenkins.get().getNodes()) {
            if (node instanceof ProxmoxInstanceAgent && this == ((ProxmoxInstanceAgent) node).getDatacenter()) {
                ProxmoxInstanceAgent agent = (ProxmoxInstanceAgent) node;
                ours.add(agent.getVmid());
                Placement.NodeState state = placement.getNode(agent.getPveNode());
                if (state != null) {
                    GuestRequirements req = agent.getRequirements();
                    state.addUsage(req.getMappings());
                    Computer computer = agent.toComputer();
                    if (computer == null || computer.isOffline()) {
                        // Not started yet, so its load does not show in the node's statistics.
                        state.addPending(req);
                    }
                }
            }
        }
        synchronized (placementLock) {
            for (Reservation r : reservations) {
                Placement.NodeState state = placement.getNode(r.node);
                if (state != null) {
                    state.addUsage(r.requirements.getMappings());
                    state.addPending(r.requirements);
                }
            }
        }
        if (countDeviceUsage) {
            JSONArray guests = pve.getClusterGuests();
            for (int i = 0; i < guests.length(); i++) {
                JSONObject guest = guests.getJSONObject(i);
                Placement.NodeState state = placement.getNode(guest.optString("node"));
                if (state == null
                        || !"qemu".equals(guest.optString("type"))
                        || !"running".equals(guest.optString("status"))
                        || ours.contains(guest.getInt("vmid"))) {
                    continue;
                }
                JSONObject config = pve.getGuestConfig(state.getName(), "qemu", guest.getInt("vmid"));
                state.addUsage(GuestRequirements.fromConfig("qemu", config).getMappings());
            }
        }
        return placement;
    }

    /** A placement decided but not yet visible as a Jenkins node. */
    private static final class Reservation {
        final String node;
        final GuestRequirements requirements;

        Reservation(String node, GuestRequirements requirements) {
            this.node = node;
            this.requirements = requirements;
        }
    }

    /** Clones a template for the label on the least loaded suitable node and returns the agent for the clone. */
    ProxmoxInstanceAgent createInstance(Label label) throws Exception {
        Connector pve = proxmoxInstance();
        Placement.Decision decision;
        Reservation reservation;
        synchronized (placementLock) {
            List<Placement.Candidate> candidates = candidates(pve, label);
            if (candidates.isEmpty()) {
                throw new IOException("No template tagged " + ProxmoxTemplate.TEMPLATE_TAG + " provides " + label);
            }
            boolean devices = false;
            for (Placement.Candidate c : candidates) {
                devices |= !c.getRequirements().getMappings().isEmpty();
            }
            Placement placement = readPlacement(pve, devices);
            List<String> reasons = new ArrayList<>();
            decision = placement.choose(candidates, reasons);
            if (decision == null) {
                throw new IOException("No Proxmox node can run " + label + " now: " + String.join("; ", reasons));
            }
            LOGGER.log(Level.INFO, "Placing {0} for {1}; nodes: {2}", new Object[] {decision, label, placement.nodesByLoad()});
            reservation = new Reservation(decision.node.getName(), decision.candidate.getRequirements());
            reservations.add(reservation);
        }
        try {
            return cloneTo(pve, decision);
        } finally {
            synchronized (placementLock) {
                reservations.remove(reservation);
            }
        }
    }

    private ProxmoxInstanceAgent cloneTo(Connector pve, Placement.Decision decision) throws Exception {
        ProxmoxTemplate template = decision.candidate.getTemplate();
        String type = template.getType();
        String target = decision.node.getName();
        String instanceId = Jenkins.get().getLegacyInstanceId();
        TemplateSettings settings = templateSettings(pve, template);
        if (lookupSshCredentials(settings.getCredentialsId()) == null) {
            throw new IOException("SSH credentials '" + settings.getCredentialsId() + "' for clones of " + template
                    + " not found; set them in the cloud or with " + TemplateSettings.CREDENTIALS
                    + " in the template notes");
        }
        boolean migrate = decision.strategy == Placement.Strategy.CLONE_AND_MIGRATE;
        // A linked clone shares the template's disks and cannot move to another node's local storage.
        boolean full = fullClone || migrate;
        String cloneTarget = decision.strategy == Placement.Strategy.CLONE_TO_TARGET ? target : null;
        ProxmoxException lastError = null;
        for (int attempt = 0; attempt < 5; attempt++) {
            int vmid = claimVmid(pve);
            try {
                String name = instanceName(template, vmid);
                String nodeUrl = ProxmoxInstanceAgent.nodeUrl(name);
                String upid;
                try {
                    upid = pve.cloneGuest(
                            template.getNode(),
                            type,
                            template.getVmid(),
                            vmid,
                            name,
                            InstanceNotes.render(instanceId, nodeUrl, null),
                            full,
                            full ? cloneStorage : null,
                            cloneTarget);
                } catch (ProxmoxException e) {
                    // Another guest may have taken the id meanwhile, so retry with a new one.
                    lastError = e;
                    LOGGER.log(Level.FINE, "Clone to " + vmid + " refused, retrying", e);
                    continue;
                }
                LOGGER.log(Level.INFO, "Cloning {0} to {1} on {2}", new Object[] {template, vmid, target});
                TaskListener listener = new LogTaskListener(LOGGER, Level.INFO);
                String current = template.getNode();
                try {
                    pve.waitForTaskOk(template.getNode(), upid, TASK_TIMEOUT_MILLIS);
                    if (cloneTarget != null) {
                        current = cloneTarget;
                    }
                    pve.updateGuestConfig(current, type, vmid, Connector.params("tags", template.instanceTags()));
                    if (migrate) {
                        LOGGER.log(Level.INFO, "Migrating {0} to {1}", new Object[] {vmid, target});
                        pve.waitForTaskOk(current, pve.migrateGuest(current, type, vmid, target), TASK_TIMEOUT_MILLIS);
                        current = target;
                    }
                    return new ProxmoxInstanceAgent(
                            name,
                            getDatacenterDescription(),
                            template,
                            current,
                            decision.candidate.getRequirements(),
                            vmid,
                            instanceId,
                            nodeUrl,
                            settings,
                            (startupTimeoutSeconds + 59) / 60 + 1);
                } catch (Exception e) {
                    try {
                        ProxmoxInstanceAgent.destroyIfOwned(
                                pve, current, type, vmid, instanceId, nodeUrl, null, listener);
                    } catch (IOException cleanup) {
                        e.addSuppressed(cleanup);
                    }
                    throw e;
                }
            } finally {
                synchronized (claimedVmids) {
                    claimedVmids.remove(vmid);
                }
            }
        }
        throw new IOException("Could not clone " + template, lastError);
    }

    /** Picks a free VM ID for a clone and reserves it until the clone exists. */
    private int claimVmid(Connector pve) throws ProxmoxException {
        synchronized (claimedVmids) {
            int vmid = firstVmid > 0 ? pve.nextVmid(firstVmid, claimedVmids) : pve.nextVmid();
            if (claimedVmids.contains(vmid)) {
                // Proxmox suggested an id another clone of this cloud is about to use.
                vmid = pve.nextVmid(vmid + 1, claimedVmids);
            }
            claimedVmids.add(vmid);
            return vmid;
        }
    }

    static String instanceName(ProxmoxTemplate template, int vmid) {
        String base = template.getName()
                .toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("^-+|-+$", "");
        String name = "jenkins-" + vmid + (base.isEmpty() ? "" : "-" + base);
        return name.length() > 63 ? name.substring(0, 63).replaceAll("-+$", "") : name;
    }

    /** Launch settings for clones of a template: its notes, with this cloud's settings as defaults. */
    TemplateSettings templateSettings(Connector pve, ProxmoxTemplate template) throws IOException {
        String notes = pve.getGuestConfig(template.getNode(), template.getType(), template.getVmid())
                .optString("description", null);
        try {
            return TemplateSettings.parse(
                    notes,
                    new TemplateSettings(guestCredentialsId, getAgentRemoteFS(), javaPath, null, DEFAULT_SSH_PORT, null));
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid notes in template " + template + ": " + e.getMessage(), e);
        }
    }

    /** Whether all connections go over SSH to the Proxmox hosts. */
    public boolean usesSsh() {
        return sshCredentialsId != null;
    }

    /** SSH connections to the Proxmox hosts, or null if connections are direct. */
    @CheckForNull
    public synchronized ProxmoxSsh proxmoxSsh() {
        if (!usesSsh()) {
            return null;
        }
        if (ssh == null) {
            ssh = new ProxmoxSsh(sshPort, () -> lookupSshCredentials(sshCredentialsId));
        }
        return ssh;
    }

    /**
     * Address of a Proxmox node: the configured host that reports being this node, otherwise the node's cluster
     * address.
     */
    public String nodeAddress(String pveNode) throws IOException {
        Connector pve = proxmoxInstance();
        synchronized (this) {
            if (nodeHosts == null
                    || !nodeHosts.containsKey(pveNode)
                    || System.currentTimeMillis() - nodeHostsTime > 10 * 60 * 1000L) {
                nodeHosts = pve.getNodesOfHosts();
                nodeHostsTime = System.currentTimeMillis();
            }
            String host = nodeHosts.get(pveNode);
            if (host != null) {
                return host;
            }
        }
        JSONArray status = pve.getClusterStatus();
        for (int i = 0; i < status.length(); i++) {
            JSONObject entry = status.getJSONObject(i);
            if ("node".equals(entry.optString("type"))
                    && pveNode.equals(entry.optString("name"))
                    && !entry.optString("ip").isEmpty()) {
                return entry.getString("ip");
            }
        }
        throw new IOException("No address known for Proxmox node " + pveNode + "; add it to the hosts");
    }

    /** A connection path to a clone's SSH server; closing it closes the forward. */
    public static final class Route implements Closeable {
        public final String host;
        public final int port;
        public final String description;

        @CheckForNull
        private final Closeable forward;

        Route(String host, int port, String description, @CheckForNull Closeable forward) {
            this.host = host;
            this.port = port;
            this.description = description;
            this.forward = forward;
        }

        @Override
        public void close() {
            if (forward != null) {
                try {
                    forward.close();
                } catch (IOException e) {
                    LOGGER.log(Level.FINE, "Closing the forward failed", e);
                }
            }
        }
    }

    /** Opens the path to {@code address:port} of a clone: through the Proxmox host running it, or direct. */
    public Route routeToGuest(String pveNode, String address, int port, TaskListener listener) throws IOException {
        ProxmoxSsh s = proxmoxSsh();
        if (s == null) {
            return new Route(address, port, "directly to " + address + ":" + port, null);
        }
        String host = nodeAddress(pveNode);
        PveHostShell.Forward forward = s.open(host, address, port, listener);
        return new Route(
                "127.0.0.1",
                forward.getLocalPort(),
                "through Proxmox host " + pveNode + " (" + host + ") to " + address + ":" + port,
                forward);
    }

    @CheckForNull
    static StandardUsernameCredentials lookupSshCredentials(@CheckForNull String id) {
        if (id == null || id.isEmpty()) {
            return null;
        }
        return CredentialsMatchers.firstOrNull(
                CredentialsProvider.lookupCredentials(
                        StandardUsernameCredentials.class,
                        Jenkins.get(),
                        ACL.SYSTEM,
                        Collections.<DomainRequirement>emptyList()),
                CredentialsMatchers.withId(id));
    }

    public boolean isProvisionFromTemplates() {
        return provisionFromTemplates;
    }

    @DataBoundSetter
    public void setProvisionFromTemplates(boolean provisionFromTemplates) {
        this.provisionFromTemplates = provisionFromTemplates;
    }

    public String getGuestCredentialsId() {
        return guestCredentialsId;
    }

    @DataBoundSetter
    public void setGuestCredentialsId(String guestCredentialsId) {
        this.guestCredentialsId = Util.fixEmptyAndTrim(guestCredentialsId);
    }

    public String getAgentRemoteFS() {
        return agentRemoteFS != null ? agentRemoteFS : DEFAULT_REMOTE_FS;
    }

    @DataBoundSetter
    public void setAgentRemoteFS(String agentRemoteFS) {
        String value = Util.fixEmptyAndTrim(agentRemoteFS);
        this.agentRemoteFS = DEFAULT_REMOTE_FS.equals(value) ? null : value;
    }

    public String getJavaPath() {
        return javaPath;
    }

    @DataBoundSetter
    public void setJavaPath(String javaPath) {
        this.javaPath = Util.fixEmptyAndTrim(javaPath);
    }

    public String getSshCredentialsId() {
        return sshCredentialsId;
    }

    @DataBoundSetter
    public void setSshCredentialsId(String sshCredentialsId) {
        this.sshCredentialsId = Util.fixEmptyAndTrim(sshCredentialsId);
    }

    public int getSshPort() {
        return sshPort;
    }

    @DataBoundSetter
    public void setSshPort(int sshPort) {
        this.sshPort = sshPort > 0 ? sshPort : DEFAULT_SSH_PORT;
    }

    public int getInstanceCap() {
        return instanceCap;
    }

    @DataBoundSetter
    public void setInstanceCap(int instanceCap) {
        this.instanceCap = instanceCap > 0 ? instanceCap : DEFAULT_INSTANCE_CAP;
    }

    public boolean isFullClone() {
        return fullClone;
    }

    @DataBoundSetter
    public void setFullClone(boolean fullClone) {
        this.fullClone = fullClone;
    }

    public String getCloneStorage() {
        return cloneStorage;
    }

    @DataBoundSetter
    public void setCloneStorage(String cloneStorage) {
        this.cloneStorage = Util.fixEmptyAndTrim(cloneStorage);
    }

    public int getFirstVmid() {
        return firstVmid;
    }

    @DataBoundSetter
    public void setFirstVmid(int firstVmid) {
        this.firstVmid = Math.max(0, firstVmid);
    }

    public int getStartupTimeoutSeconds() {
        return startupTimeoutSeconds;
    }

    @DataBoundSetter
    public void setStartupTimeoutSeconds(int startupTimeoutSeconds) {
        this.startupTimeoutSeconds = startupTimeoutSeconds > 0 ? startupTimeoutSeconds : DEFAULT_STARTUP_TIMEOUT;
    }

    public String getHostname() {
        return hostname;
    }

    public String getUsername() {
        return username;
    }

    public String getRealm() {
        return realm;
    }

    public Secret getPassword() {
        return password;
    }

    public Boolean getIgnoreSSL() {
        return ignoreSSL;
    }

    public String getDatacenterDescription() {
        return username + "@" + realm + " - " + hostname;
    }

    @Override
    public DescriptorImpl getDescriptor() {
        return (DescriptorImpl) super.getDescriptor();
    }

    public Connector proxmoxInstance() {
        if (pveConnector == null) {
            pveConnector = new Connector(hostname, username, realm, password, ignoreSSL, proxmoxSsh());
        }
        return pveConnector;
    }

    public List<String> getNodes() {
        Connector pveConnector = proxmoxInstance();
        try {
            return pveConnector.getNodes();
        } catch (LoginException e) {
            return new ArrayList<String>();
        }
    }

    public HashMap<String, Integer> getQemuMachines(String node) {
        if (node == null || node.isEmpty()) {
            return new HashMap<String, Integer>();
        }

        Connector pveConnector = proxmoxInstance();
        try {
            return pveConnector.getQemuMachines(node);
        } catch (LoginException e) {
            return new HashMap<String, Integer>();
        }
    }

    public List<String> getQemuMachineSnapshots(String node, Integer vmid) {
        if (node == null || node.isEmpty() || vmid < 1) {
            return new ArrayList<String>();
        }

        Connector pveConnector = proxmoxInstance();
        try {
            return pveConnector.getQemuMachineSnapshots(node, vmid);
        } catch (LoginException e) {
            return new ArrayList<String>();
        }
    }

    @Extension
    public static final class DescriptorImpl extends Descriptor<Cloud> {
        public String getDisplayName() {
            return "Proxmox Datacenter";
        }

        @Override
        public boolean configure(StaplerRequest req, net.sf.json.JSONObject o) throws FormException {
            save();
            return super.configure(req, o);
        }

        private FormValidation fieldNotSpecifiedError(String fieldName) {
            return FormValidation.error(fieldName + " not specified");
        }

        private FormValidation emptyStringValidation(String fieldName, String value) {
            if (Util.fixEmptyAndTrim(value) == null) return fieldNotSpecifiedError(fieldName);
            else return FormValidation.ok();
        }

        public FormValidation doCheckHostname(@QueryParameter String value) {
            return emptyStringValidation("Hostname", value);
        }

        public FormValidation doCheckUsername(@QueryParameter String value) {
            return emptyStringValidation("Username", value);
        }

        public FormValidation doCheckRealm(@QueryParameter String value) {
            return emptyStringValidation("Realm", value);
        }

        public FormValidation doCheckPassword(@QueryParameter Secret value) {
            return emptyStringValidation("Password", value.getPlainText());
        }

        private static ListBoxModel sshCredentialItems(String current) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return new StandardUsernameListBoxModel().includeCurrentValue(current);
            }
            return new StandardUsernameListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM,
                            Jenkins.get(),
                            StandardUsernameCredentials.class,
                            Collections.<DomainRequirement>emptyList(),
                            SSHAuthenticator.matcher(Connection.class))
                    .includeCurrentValue(current);
        }

        public ListBoxModel doFillGuestCredentialsIdItems(@QueryParameter String guestCredentialsId) {
            return sshCredentialItems(guestCredentialsId);
        }

        public ListBoxModel doFillSshCredentialsIdItems(@QueryParameter String sshCredentialsId) {
            return sshCredentialItems(sshCredentialsId);
        }

        @POST
        public FormValidation doTestProvisioning(
                @QueryParameter String hostname,
                @QueryParameter String username,
                @QueryParameter String realm,
                @QueryParameter Secret password,
                @QueryParameter Boolean ignoreSSL,
                @QueryParameter String guestCredentialsId,
                @QueryParameter String agentRemoteFS,
                @QueryParameter String javaPath,
                @QueryParameter String sshCredentialsId,
                @QueryParameter int sshPort) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            Datacenter dc = new Datacenter(hostname, username, realm, password, ignoreSSL != null && ignoreSSL);
            dc.setGuestCredentialsId(guestCredentialsId);
            dc.setAgentRemoteFS(agentRemoteFS);
            dc.setJavaPath(javaPath);
            dc.setSshCredentialsId(sshCredentialsId);
            dc.setSshPort(sshPort);
            StringBuilder report = new StringBuilder();
            boolean problems = false;
            ProxmoxSsh s = dc.proxmoxSsh();
            try {
                if (s != null) {
                    for (Connector.Host h : Connector.parseHosts(hostname)) {
                        report.append("SSH to ").append(h.name).append(": ");
                        try {
                            s.connection(h.name, TaskListener.NULL);
                            report.append("connected\n");
                        } catch (IOException e) {
                            report.append("PROBLEM: ").append(e.getMessage()).append('\n');
                            problems = true;
                        }
                    }
                } else {
                    report.append("No SSH credentials: Jenkins connects to the API and the clones directly\n");
                }
                Connector pve = dc.proxmoxInstance();
                Map<String, String> nodesOfHosts = pve.getNodesOfHosts();
                report.append("Proxmox API: logged in; hosts: ");
                for (Connector.Host h : pve.getHosts()) {
                    String node = null;
                    for (Map.Entry<String, String> e : nodesOfHosts.entrySet()) {
                        if (e.getValue().equals(h.name)) {
                            node = e.getKey();
                        }
                    }
                    report.append(h).append(node == null ? " (not reachable)" : " = node " + node).append("; ");
                }
                report.append('\n');
                List<ProxmoxTemplate> templates = readTemplates(pve);
                if (templates.isEmpty()) {
                    return FormValidation.warning(
                            "Connected, but no templates are tagged " + ProxmoxTemplate.TEMPLATE_TAG);
                }
                Placement placement = dc.readPlacement(pve, true);
                report.append("Nodes:\n");
                for (Placement.NodeState n : placement.nodesByLoad()) {
                    report.append("  ").append(n).append('\n');
                }
                Set<String> nodes = new TreeSet<>();
                for (ProxmoxTemplate t : templates) {
                    report.append(t).append("\n  ");
                    try {
                        TemplateSettings settings = dc.templateSettings(pve, t);
                        report.append(settings);
                        if (lookupSshCredentials(settings.getCredentialsId()) == null) {
                            report.append("\n  PROBLEM: credentials not found");
                            problems = true;
                        }
                        Placement.Candidate c = new Placement.Candidate(
                                t,
                                GuestRequirements.fromConfig(
                                        t.getType(), pve.getGuestConfig(t.getNode(), t.getType(), t.getVmid())));
                        report.append("\n  needs ").append(c.getRequirements());
                        boolean anywhere = false;
                        for (Placement.NodeState n : placement.getNodes()) {
                            String blocker = placement.blocker(c, n);
                            report.append("\n  ").append(n.getName()).append(": ");
                            report.append(blocker == null ? "can run it" : blocker);
                            anywhere |= blocker == null;
                            if (blocker == null) {
                                nodes.add(n.getName());
                            }
                        }
                        if (!anywhere) {
                            report.append("\n  PROBLEM: no node can run it now");
                            problems = true;
                        }
                    } catch (IOException e) {
                        report.append("PROBLEM: ").append(e.getMessage());
                        problems = true;
                    }
                    report.append('\n');
                }
                for (String node : nodes) {
                    report.append("Clones on ").append(node).append(": ");
                    if (s == null) {
                        report.append("connected directly\n");
                        continue;
                    }
                    try {
                        String host = dc.nodeAddress(node);
                        s.connection(host, TaskListener.NULL);
                        report.append("through ").append(host).append('\n');
                    } catch (IOException e) {
                        report.append("PROBLEM: ").append(e.getMessage()).append('\n');
                        problems = true;
                    }
                }
                String html = "<pre>" + Util.escape(report.toString()) + "</pre>";
                return problems ? FormValidation.errorWithMarkup(html) : FormValidation.okWithMarkup(html);
            } catch (IOException | RuntimeException e) {
                return FormValidation.error(e, report + "Failed: " + e.getMessage());
            } finally {
                if (s != null) {
                    s.close();
                }
            }
        }

        @POST
        public FormValidation doTestConnection(
                @QueryParameter String hostname,
                @QueryParameter String username,
                @QueryParameter String realm,
                @QueryParameter Secret password,
                @QueryParameter Boolean ignoreSSL,
                @QueryParameter String sshCredentialsId,
                @QueryParameter int sshPort) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
            Datacenter dc = null;
            try {
                if (hostname.isEmpty()) {
                    return fieldNotSpecifiedError("Hostname");
                }
                if (username.isEmpty()) {
                    return fieldNotSpecifiedError("Username");
                }
                if (realm.isEmpty()) {
                    return fieldNotSpecifiedError("Realm");
                }
                if (password.getPlainText().isEmpty()) {
                    return fieldNotSpecifiedError("Password");
                }

                dc = new Datacenter(hostname, username, realm, password, ignoreSSL != null && ignoreSSL);
                dc.setSshCredentialsId(sshCredentialsId);
                dc.setSshPort(sshPort);
                dc.proxmoxInstance().login();
                return FormValidation.ok("Login successful" + (dc.usesSsh() ? " (API tunneled over SSH)" : ""));

            } catch (RuntimeException e) {
                LOGGER.log(Level.WARNING, "Connection error", e);
                return FormValidation.error(e, "Connection failed: " + e.getMessage());
            } catch (LoginException e) {
                LOGGER.log(Level.SEVERE, "Authentication error", e);
                return FormValidation.error(
                        "Authentication error. Please verify your login credentials or check logs.");
            } finally {
                ProxmoxSsh s = dc == null ? null : dc.proxmoxSsh();
                if (s != null) {
                    s.close();
                }
            }
        }
    }
}
