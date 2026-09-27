package org.jenkinsci.plugins.proxmox.provisioning;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import hudson.model.Label;
import hudson.model.labels.LabelAtom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import kong.unirest.json.JSONObject;

/**
 * A Proxmox template (QEMU or LXC) tagged with {@value #TEMPLATE_TAG}. Its other tags are the Jenkins labels
 * it provides.
 */
public final class ProxmoxTemplate {

    /** Tag marking a template as usable by Jenkins. */
    public static final String TEMPLATE_TAG = "jenkins-template";

    /** Tag put on clones created by Jenkins instead of {@link #TEMPLATE_TAG}. */
    public static final String INSTANCE_TAG = "jenkins-instance";

    private final String node;
    private final String type;
    private final int vmid;
    private final String name;
    private final List<String> labels;

    public ProxmoxTemplate(String node, String type, int vmid, String name, List<String> labels) {
        this.node = node;
        this.type = type;
        this.vmid = vmid;
        this.name = name;
        this.labels = Collections.unmodifiableList(new ArrayList<>(labels));
    }

    /**
     * Creates a template from a {@code /cluster/resources} entry.
     * @return the template, or null if the entry is not a template tagged {@value #TEMPLATE_TAG}
     */
    @CheckForNull
    public static ProxmoxTemplate fromResource(JSONObject resource) {
        if (resource.optInt("template", 0) != 1) {
            return null;
        }
        String type = resource.optString("type");
        if (!"qemu".equals(type) && !"lxc".equals(type)) {
            return null;
        }
        Set<String> tags = parseTags(resource.optString("tags", ""));
        if (!tags.contains(TEMPLATE_TAG)) {
            return null;
        }
        List<String> labels = tags.stream().filter(t -> !t.equals(TEMPLATE_TAG)).collect(Collectors.toList());
        return new ProxmoxTemplate(
                resource.getString("node"),
                type,
                resource.getInt("vmid"),
                resource.optString("name", type + "-" + resource.getInt("vmid")),
                labels);
    }

    /** Splits a Proxmox tag list ({@code ;}, {@code ,} or space separated), lower case, keeping order. */
    public static Set<String> parseTags(String tags) {
        return Arrays.stream(tags.split("[;,\\s]+"))
                .map(t -> t.trim().toLowerCase(Locale.ROOT))
                .filter(t -> !t.isEmpty())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    /** Tags for a clone of this template: {@value #TEMPLATE_TAG} replaced by {@value #INSTANCE_TAG}. */
    public String instanceTags() {
        List<String> tags = new ArrayList<>();
        tags.add(INSTANCE_TAG);
        tags.addAll(labels);
        return String.join(";", tags);
    }

    public boolean matches(@CheckForNull Label label) {
        if (label == null) {
            return false;
        }
        Set<LabelAtom> atoms = labels.stream().map(LabelAtom::get).collect(Collectors.toSet());
        return label.matches(atoms);
    }

    public String getNode() {
        return node;
    }

    /** {@code qemu} or {@code lxc}. */
    public String getType() {
        return type;
    }

    public int getVmid() {
        return vmid;
    }

    public String getName() {
        return name;
    }

    public List<String> getLabels() {
        return labels;
    }

    public String getLabelString() {
        return String.join(" ", labels);
    }

    @Override
    public String toString() {
        return name + " (" + type + " " + vmid + " on " + node + ", labels: " + getLabelString() + ")";
    }
}
