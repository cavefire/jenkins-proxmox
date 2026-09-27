package org.jenkinsci.plugins.proxmox.provisioning;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * {@code jenkins-<key>: <value>} lines in Proxmox notes. Used for the notes Jenkins writes into a clone, and for the
 * launch settings in a template's notes (see {@link TemplateSettings}).
 *
 * <p>The notes (description) Jenkins writes into a clone They identify the Jenkins instance, the agent node and the
 * build using the clone, and are checked before the clone is deleted.
 */
public final class InstanceNotes {

    public static final String INSTANCE_ID = "jenkins-instance-id";
    public static final String NODE = "jenkins-node";
    public static final String BUILD = "jenkins-build";

    private static final Pattern LINE = Pattern.compile("^[\\s\\-*]*(jenkins-[a-z-]+):[ \\t]*(.*?)\\s*$");

    private InstanceNotes() {}

    public static String render(String instanceId, String nodeUrl, @CheckForNull String buildUrl) {
        StringBuilder sb = new StringBuilder();
        sb.append("Jenkins agent created from a template by the Jenkins Proxmox plugin.\n");
        sb.append("It is deleted automatically when the build below has finished. Do not edit these lines.\n\n");
        sb.append("- ").append(INSTANCE_ID).append(": ").append(instanceId).append('\n');
        sb.append("- ").append(NODE).append(": ").append(nodeUrl).append('\n');
        if (buildUrl != null) {
            sb.append("- ").append(BUILD).append(": ").append(buildUrl).append('\n');
        }
        return sb.toString();
    }

    /** @return the {@code jenkins-*} keys found in the notes; empty if there are none */
    public static Map<String, String> parse(@CheckForNull String notes) {
        Map<String, String> result = new LinkedHashMap<>();
        if (notes == null) {
            return result;
        }
        for (String line : notes.split("\\R")) {
            Matcher m = LINE.matcher(line);
            if (m.matches() && !m.group(2).isEmpty()) {
                result.putIfAbsent(m.group(1), m.group(2));
            }
        }
        return result;
    }

    /**
     * Decides whether Jenkins may delete a clone with the given notes.
     *
     * @param buildUrl the build that used the clone, or null if none did
     * @return null if deletion is allowed, otherwise the reason it is not
     */
    @CheckForNull
    public static String deletionBlocker(
            @CheckForNull String notes, String instanceId, String nodeUrl, @CheckForNull String buildUrl) {
        Map<String, String> values = parse(notes);
        if (!instanceId.equals(values.get(INSTANCE_ID))) {
            return "notes do not name this Jenkins instance (" + INSTANCE_ID + ": " + values.get(INSTANCE_ID) + ")";
        }
        if (!nodeUrl.equals(values.get(NODE))) {
            return "notes name node " + values.get(NODE) + " instead of " + nodeUrl;
        }
        String notedBuild = values.get(BUILD);
        if (buildUrl == null ? notedBuild != null : !buildUrl.equals(notedBuild)) {
            return "notes name build " + notedBuild + " instead of " + buildUrl;
        }
        return null;
    }
}
