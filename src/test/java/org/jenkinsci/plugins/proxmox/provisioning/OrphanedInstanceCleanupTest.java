package org.jenkinsci.plugins.proxmox.provisioning;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import org.junit.Test;

public class OrphanedInstanceCleanupTest {

    @Test
    public void nodeNameFromUrl() {
        assertThat(OrphanedInstanceCleanup.nodeName("https://j/computer/jenkins-1-a%20b/"), is("jenkins-1-a b"));
    }
}
