package org.jenkinsci.plugins.proxmox.provisioning;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Chooses the Proxmox node and template for a new clone: the least loaded online node that has every mapped
 * resource the template needs, with a free device for each, and that can get the template's disks.
 */
public final class Placement {

    /** How the clone gets onto the chosen node. */
    public enum Strategy {
        /** The template is on the chosen node. */
        LOCAL,
        /** The template's disks are on shared storage, so the clone is created directly on the chosen node. */
        CLONE_TO_TARGET,
        /** Local storage: full clone on the template's node, then offline migration to the chosen node. */
        CLONE_AND_MIGRATE
    }

    /** A template with its requirements. */
    public static final class Candidate {
        final ProxmoxTemplate template;
        final GuestRequirements requirements;

        public Candidate(ProxmoxTemplate template, GuestRequirements requirements) {
            this.template = template;
            this.requirements = requirements;
        }

        public ProxmoxTemplate getTemplate() {
            return template;
        }

        public GuestRequirements getRequirements() {
            return requirements;
        }
    }

    /** Load and resources of a node. */
    public static final class NodeState {
        final String name;
        final double cpu;
        final int maxCpu;
        final long mem;
        final long maxMem;
        /** Storages available on the node, and whether each is shared. */
        final Map<String, Boolean> storages;

        long pendingMem;
        int pendingCores;
        final Map<String, Integer> usedMappings = new HashMap<>();

        public NodeState(String name, double cpu, int maxCpu, long mem, long maxMem, Map<String, Boolean> storages) {
            this.name = name;
            this.cpu = cpu;
            this.maxCpu = Math.max(1, maxCpu);
            this.mem = mem;
            this.maxMem = Math.max(1, maxMem);
            this.storages = storages;
        }

        /** Load between 0 and 1 (can exceed 1 when overcommitted): average of CPU and memory use. */
        public double load() {
            double cpuLoad = cpu + (double) pendingCores / maxCpu;
            double memLoad = (double) (mem + pendingMem) / maxMem;
            return (cpuLoad + memLoad) / 2;
        }

        public String getName() {
            return name;
        }

        /** Accounts for a guest that will run on this node but is not visible in its load yet. */
        public void addPending(GuestRequirements requirements) {
            pendingMem += requirements.getMemoryBytes();
            pendingCores += requirements.getCores();
        }

        /** Accounts for a guest on this node that uses mapped devices. */
        public void addUsage(Set<String> mappings) {
            for (String m : mappings) {
                usedMappings.merge(m, 1, Integer::sum);
            }
        }

        @Override
        public String toString() {
            return String.format(
                    "%s: load %.0f%% (cpu %.0f%%, memory %.0f%%)",
                    name, load() * 100, cpu * 100, (double) mem / maxMem * 100);
        }
    }

    /** A cluster-wide resource mapping. */
    public static final class Mapping {
        /** Devices of the mapping per node. */
        final Map<String, Integer> devicesPerNode;
        /** Mediated devices (e.g. vGPUs) can be shared, so their use is not counted. */
        final boolean shareable;

        public Mapping(Map<String, Integer> devicesPerNode, boolean shareable) {
            this.devicesPerNode = devicesPerNode;
            this.shareable = shareable;
        }
    }

    /** The chosen template and node. */
    public static final class Decision {
        public final Candidate candidate;
        public final NodeState node;
        public final Strategy strategy;

        Decision(Candidate candidate, NodeState node, Strategy strategy) {
            this.candidate = candidate;
            this.node = node;
            this.strategy = strategy;
        }

        @Override
        public String toString() {
            return candidate.template.getName() + " on " + node.name + " (" + strategy + ")";
        }
    }

    private final Map<String, NodeState> nodes;
    private final Map<String, Mapping> mappings;

    /**
     * @param nodes online nodes
     * @param mappings resource mappings by key ({@link GuestRequirements#mappingKey})
     */
    public Placement(List<NodeState> nodes, Map<String, Mapping> mappings) {
        this.nodes = new LinkedHashMap<>();
        for (NodeState n : nodes) {
            this.nodes.put(n.name, n);
        }
        this.mappings = mappings;
    }

    @CheckForNull
    public NodeState getNode(String name) {
        return nodes.get(name);
    }

    public List<NodeState> getNodes() {
        return new ArrayList<>(nodes.values());
    }

    /**
     * @return why the candidate cannot run on the node, or null if it can
     */
    @CheckForNull
    public String blocker(Candidate candidate, NodeState node) {
        GuestRequirements req = candidate.requirements;
        boolean local = node.name.equals(candidate.template.getNode());
        if (req.isPinned() && !local) {
            return "uses devices without a resource mapping, so it can only run on " + candidate.template.getNode();
        }
        for (String key : req.getMappings()) {
            Mapping mapping = mappings.get(key);
            if (mapping == null) {
                return "resource mapping " + key + " does not exist";
            }
            int devices = mapping.devicesPerNode.getOrDefault(node.name, 0);
            if (devices == 0) {
                return "has no device for mapping " + key;
            }
            if (!mapping.shareable && node.usedMappings.getOrDefault(key, 0) >= devices) {
                return "all " + devices + " device(s) of mapping " + key + " are in use";
            }
        }
        if (!local) {
            for (String storage : req.getStorages()) {
                if (!node.storages.containsKey(storage)) {
                    return "has no storage " + storage;
                }
            }
        }
        return null;
    }

    Strategy strategy(Candidate candidate, NodeState node) {
        if (node.name.equals(candidate.template.getNode())) {
            return Strategy.LOCAL;
        }
        for (String storage : candidate.requirements.getStorages()) {
            if (!Boolean.TRUE.equals(node.storages.get(storage))) {
                return Strategy.CLONE_AND_MIGRATE;
            }
        }
        return Strategy.CLONE_TO_TARGET;
    }

    /**
     * Picks the least loaded node any candidate can run on. On equal load, a template already on that node wins.
     *
     * @param reasons receives why nodes were ruled out
     * @return the decision, or null if no candidate fits anywhere
     */
    @CheckForNull
    public Decision choose(List<Candidate> candidates, List<String> reasons) {
        Decision best = null;
        for (Candidate candidate : candidates) {
            for (NodeState node : nodes.values()) {
                String blocker = blocker(candidate, node);
                if (blocker != null) {
                    reasons.add(candidate.template.getName() + " on " + node.name + ": " + blocker);
                    continue;
                }
                Decision option = new Decision(candidate, node, strategy(candidate, node));
                if (best == null || better(option, best)) {
                    best = option;
                }
            }
        }
        return best;
    }

    private static boolean better(Decision a, Decision b) {
        int byLoad = Double.compare(a.node.load(), b.node.load());
        if (byLoad != 0) {
            return byLoad < 0;
        }
        return a.strategy.ordinal() < b.strategy.ordinal();
    }

    /** Nodes sorted by load, for reporting. */
    public List<NodeState> nodesByLoad() {
        List<NodeState> list = getNodes();
        Collections.sort(list, (a, b) -> Double.compare(a.load(), b.load()));
        return list;
    }
}
