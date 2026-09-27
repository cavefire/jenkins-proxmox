package org.jenkinsci.plugins.proxmox.provisioning;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import kong.unirest.json.JSONObject;
import org.junit.Test;

public class ProxmoxTemplateTest {

    static JSONObject resource(int template, String tags) {
        return new JSONObject()
                .put("vmid", 9000)
                .put("name", "ubuntu-2404")
                .put("node", "pve1")
                .put("type", "lxc")
                .put("template", template)
                .put("tags", tags);
    }

    @Test
    public void onlyTaggedTemplatesAreUsed() {
        assertThat(ProxmoxTemplate.fromResource(resource(1, "jenkins-template;ubuntu2404")), notNullValue());
        assertThat(ProxmoxTemplate.fromResource(resource(0, "jenkins-template;ubuntu2404")), nullValue());
        assertThat(ProxmoxTemplate.fromResource(resource(1, "ubuntu2404")), nullValue());
        assertThat(ProxmoxTemplate.fromResource(resource(1, "").put("type", "storage")), nullValue());
    }

    @Test
    public void otherTagsAreLabels() {
        ProxmoxTemplate t = ProxmoxTemplate.fromResource(resource(1, "ubuntu2404;jenkins-template;Docker"));
        assertThat(t.getLabels(), contains("ubuntu2404", "docker"));
        assertThat(t.instanceTags(), is("jenkins-instance;ubuntu2404;docker"));
        assertThat(t.getNode(), is("pve1"));
        assertThat(t.getVmid(), is(9000));
    }
}
