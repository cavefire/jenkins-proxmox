package org.jenkinsci.plugins.proxmox.provisioning;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.Extension;
import hudson.model.AsyncPeriodicWork;
import hudson.model.Job;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.slaves.Cloud;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import jenkins.model.Jenkins;
import kong.unirest.json.JSONArray;
import kong.unirest.json.JSONObject;
import org.jenkinsci.plugins.proxmox.Datacenter;
import org.jenkinsci.plugins.proxmox.pve2api.Connector;

/**
 * Deletes clones left behind by this Jenkins, e.g. after a crash: guests tagged
 * {@value ProxmoxTemplate#INSTANCE_TAG} whose notes name this Jenkins instance and a node that no longer exists,
 * and whose noted build (if any) is not running. A clone must look orphaned for {@link #GRACE_MILLIS} before it is
 * deleted, so clones that are still being set up are left alone.
 */
@Extension
public class OrphanedInstanceCleanup extends AsyncPeriodicWork {

    static final long GRACE_MILLIS = TimeUnit.MINUTES.toMillis(15);

    /** First time each guest ("node/vmid") was seen orphaned. */
    private final Map<String, Long> firstSeen = new ConcurrentHashMap<>();

    public OrphanedInstanceCleanup() {
        super("Proxmox orphaned instance cleanup");
    }

    @Override
    public long getRecurrencePeriod() {
        return TimeUnit.MINUTES.toMillis(5);
    }

    @Override
    protected void execute(TaskListener listener) throws IOException, InterruptedException {
        Set<String> seen = new HashSet<>();
        for (Cloud cloud : Jenkins.get().clouds) {
            if (cloud instanceof Datacenter) {
                sweep(((Datacenter) cloud).proxmoxInstance(), listener, seen);
            }
        }
        firstSeen.keySet().retainAll(seen);
    }

    private void sweep(Connector pve, TaskListener listener, Set<String> seen)
            throws IOException, InterruptedException {
        String instanceId = Jenkins.get().getLegacyInstanceId();
        JSONArray guests = pve.getClusterGuests();
        for (int i = 0; i < guests.length(); i++) {
            JSONObject guest = guests.getJSONObject(i);
            if (guest.optInt("template", 0) == 1
                    || !ProxmoxTemplate.parseTags(guest.optString("tags", "")).contains(ProxmoxTemplate.INSTANCE_TAG)) {
                continue;
            }
            String node = guest.getString("node");
            String type = guest.getString("type");
            int vmid = guest.getInt("vmid");
            Map<String, String> notes =
                    InstanceNotes.parse(pve.getGuestConfig(node, type, vmid).optString("description", null));
            String nodeUrl = notes.get(InstanceNotes.NODE);
            if (!instanceId.equals(notes.get(InstanceNotes.INSTANCE_ID)) || nodeUrl == null) {
                continue;
            }
            if (Jenkins.get().getNode(nodeName(nodeUrl)) != null) {
                continue;
            }
            String buildUrl = notes.get(InstanceNotes.BUILD);
            Run<?, ?> run = buildUrl == null ? null : findRun(buildUrl);
            if (run != null && run.isBuilding()) {
                continue;
            }
            String key = node + "/" + vmid;
            seen.add(key);
            long since = firstSeen.computeIfAbsent(key, k -> System.currentTimeMillis());
            if (System.currentTimeMillis() - since < GRACE_MILLIS) {
                continue;
            }
            listener.getLogger().println("Deleting orphaned Proxmox guest " + vmid + " (" + nodeUrl + ")");
            try {
                ProxmoxInstanceAgent.destroyIfOwned(pve, node, type, vmid, instanceId, nodeUrl, buildUrl, listener);
                firstSeen.remove(key);
            } catch (IOException e) {
                listener.error("Deleting Proxmox guest " + vmid + " failed: " + e.getMessage());
            }
        }
    }

    /** Last path segment of a {@code .../computer/<name>/} URL. */
    static String nodeName(String nodeUrl) {
        String path = nodeUrl.endsWith("/") ? nodeUrl.substring(0, nodeUrl.length() - 1) : nodeUrl;
        return decode(path.substring(path.lastIndexOf('/') + 1));
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Resolves a build URL ({@code .../job/a/job/b/42/}) to the build, if it still exists. */
    @CheckForNull
    static Run<?, ?> findRun(String buildUrl) {
        String[] parts = buildUrl.split("/");
        List<String> names = new ArrayList<>();
        Integer number = null;
        for (int i = 0; i < parts.length; i++) {
            if (parts[i].equals("job") && i + 1 < parts.length) {
                names.add(decode(parts[++i]));
                number = null;
            } else if (!names.isEmpty() && parts[i].matches("\\d+")) {
                number = Integer.valueOf(parts[i]);
            }
        }
        if (names.isEmpty() || number == null) {
            return null;
        }
        Job<?, ?> job = Jenkins.get().getItemByFullName(String.join("/", names), Job.class);
        return job == null ? null : job.getBuildByNumber(number);
    }
}
