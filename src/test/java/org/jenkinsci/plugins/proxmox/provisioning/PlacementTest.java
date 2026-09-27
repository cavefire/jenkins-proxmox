package org.jenkinsci.plugins.proxmox.provisioning;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.Test;

public class PlacementTest {

    private static final long GB = 1024L * 1024 * 1024;

    private static Placement.NodeState node(String name, double cpu, long memGb, boolean sharedCeph) {
        Map<String, Boolean> storages = new HashMap<>();
        storages.put("local-lvm", false);
        if (sharedCeph) {
            storages.put("ceph", true);
        }
        return new Placement.NodeState(name, cpu, 16, memGb * GB, 64 * GB, storages);
    }

    private static Placement.Candidate template(String node, String storage, boolean pinned, String... mappings) {
        ProxmoxTemplate t = new ProxmoxTemplate(node, "qemu", 9000, "tpl-" + node, Collections.singletonList("x"));
        return new Placement.Candidate(
                t,
                new GuestRequirements(
                        new HashSet<>(Arrays.asList(mappings)),
                        pinned,
                        Collections.singleton(storage),
                        8 * GB,
                        4));
    }

    private static Map<String, Placement.Mapping> gpuOn(String... nodes) {
        Map<String, Integer> perNode = new HashMap<>();
        for (String n : nodes) {
            perNode.put(n, 1);
        }
        Map<String, Placement.Mapping> m = new HashMap<>();
        m.put("pci:gpu", new Placement.Mapping(perNode, false));
        return m;
    }

    @Test
    public void leastLoadedNodeWins() {
        Placement p = new Placement(
                Arrays.asList(node("a", 0.8, 40, true), node("b", 0.1, 10, true), node("c", 0.5, 30, true)),
                Collections.emptyMap());
        Placement.Decision d = p.choose(Collections.singletonList(template("a", "ceph", false)), new ArrayList<>());
        assertThat(d.node.getName(), is("b"));
        assertThat(d.strategy, is(Placement.Strategy.CLONE_TO_TARGET));
    }

    @Test
    public void localStorageMigrates() {
        Placement p = new Placement(
                Arrays.asList(node("a", 0.8, 40, false), node("b", 0.1, 10, false)), Collections.emptyMap());
        Placement.Decision d = p.choose(Collections.singletonList(template("a", "local-lvm", false)), new ArrayList<>());
        assertThat(d.node.getName(), is("b"));
        assertThat(d.strategy, is(Placement.Strategy.CLONE_AND_MIGRATE));
    }

    @Test
    public void templateCopyOnTargetNodeIsPreferred() {
        Placement p = new Placement(
                Arrays.asList(node("a", 0.8, 40, false), node("b", 0.1, 10, false)), Collections.emptyMap());
        Placement.Decision d = p.choose(
                Arrays.asList(template("a", "local-lvm", false), template("b", "local-lvm", false)),
                new ArrayList<>());
        assertThat(d.node.getName(), is("b"));
        assertThat(d.strategy, is(Placement.Strategy.LOCAL));
        assertThat(d.candidate.getTemplate().getNode(), is("b"));
    }

    @Test
    public void onlyNodesWithMappedDevice() {
        Placement p = new Placement(
                Arrays.asList(node("a", 0.8, 40, true), node("b", 0.1, 10, true), node("c", 0.5, 30, true)),
                gpuOn("a", "c"));
        List<String> reasons = new ArrayList<>();
        Placement.Decision d = p.choose(Collections.singletonList(template("a", "ceph", false, "pci:gpu")), reasons);
        assertThat(d.node.getName(), is("c"));
        assertThat(reasons, hasItem(containsString("b: has no device for mapping pci:gpu")));
    }

    @Test
    public void busyDevicesAreSkipped() {
        Placement.NodeState a = node("a", 0.8, 40, true);
        Placement.NodeState c = node("c", 0.5, 30, true);
        c.addUsage(Collections.singleton("pci:gpu"));
        Placement p = new Placement(Arrays.asList(a, c), gpuOn("a", "c"));
        Placement.Decision d = p.choose(Collections.singletonList(template("a", "ceph", false, "pci:gpu")), new ArrayList<>());
        assertThat(d.node.getName(), is("a"));

        a.addUsage(Collections.singleton("pci:gpu"));
        List<String> reasons = new ArrayList<>();
        assertThat(p.choose(Collections.singletonList(template("a", "ceph", false, "pci:gpu")), reasons), nullValue());
        assertThat(reasons, hasItem(containsString("in use")));
    }

    @Test
    public void unknownMappingBlocks() {
        Placement p = new Placement(Collections.singletonList(node("a", 0, 1, true)), Collections.emptyMap());
        List<String> reasons = new ArrayList<>();
        assertThat(p.choose(Collections.singletonList(template("a", "ceph", false, "usb:dongle")), reasons), nullValue());
        assertThat(reasons, hasItem(containsString("does not exist")));
    }

    @Test
    public void pinnedTemplateStaysOnItsNode() {
        Placement p = new Placement(
                Arrays.asList(node("a", 0.8, 40, true), node("b", 0.1, 10, true)), Collections.emptyMap());
        Placement.Decision d = p.choose(Collections.singletonList(template("a", "ceph", true)), new ArrayList<>());
        assertThat(d.node.getName(), is("a"));
    }

    @Test
    public void pendingClonesSpreadLoad() {
        Placement.NodeState a = node("a", 0.2, 20, true);
        Placement.NodeState b = node("b", 0.2, 20, true);
        Placement p = new Placement(Arrays.asList(a, b), Collections.emptyMap());
        Placement.Candidate t = template("a", "ceph", false);
        Placement.Decision first = p.choose(Collections.singletonList(t), new ArrayList<>());
        first.node.addPending(t.getRequirements());
        Placement.Decision second = p.choose(Collections.singletonList(t), new ArrayList<>());
        assertThat(second.node.getName(), is(first.node.getName().equals("a") ? "b" : "a"));
    }

    @Test
    public void missingStorageOnTargetBlocks() {
        Placement p = new Placement(
                Arrays.asList(node("a", 0.9, 60, true), node("b", 0.1, 10, false)), Collections.emptyMap());
        Placement.Decision d = p.choose(Collections.singletonList(template("a", "ceph", false)), new ArrayList<>());
        assertThat(d.node.getName(), is("a"));
    }
}
