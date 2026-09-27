package org.jenkinsci.plugins.proxmox.pve2api;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import org.junit.Test;

public class ConnectorTest {

    @Test
    public void firstFreeVmid() {
        assertThat(Connector.firstFree(Collections.emptySet(), 10000), is(10000));
        assertThat(Connector.firstFree(new HashSet<>(Arrays.asList(10000, 10001, 10003)), 10000), is(10002));
        assertThat(Connector.firstFree(new HashSet<>(Arrays.asList(100, 101)), 0), is(102));
    }

    @Test
    public void parsesClusterHosts() {
        assertThat(
                Connector.parseHosts("pve1, pve2:8007 10.0.0.3").toString(), is("[pve1, pve2:8007, 10.0.0.3]"));
        assertThat(Connector.parseHosts("pve1").get(0).port, is(8006));
    }

    @Test
    public void encodesRepeatedParameters() {
        assertThat(
                Connector.encodeParams(Connector.params("command", Arrays.asList("a b", "c"), "x", null, "n", 1)),
                is("command=a+b&command=c&n=1"));
    }
}
