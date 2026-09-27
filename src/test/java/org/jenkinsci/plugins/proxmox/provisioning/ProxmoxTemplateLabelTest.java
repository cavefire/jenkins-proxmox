package org.jenkinsci.plugins.proxmox.provisioning;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

import hudson.model.Label;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class ProxmoxTemplateLabelTest {

    @Rule
    public JenkinsRule r = new JenkinsRule();

    @Test
    public void matchesLabelExpressions() throws Exception {
        ProxmoxTemplate t =
                ProxmoxTemplate.fromResource(ProxmoxTemplateTest.resource(1, "jenkins-template;ubuntu2404;docker"));
        assertThat(t.matches(Label.parseExpression("ubuntu2404")), is(true));
        assertThat(t.matches(Label.parseExpression("ubuntu2404 && docker")), is(true));
        assertThat(t.matches(Label.parseExpression("ubuntu2204")), is(false));
        assertThat(t.matches(null), is(false));
    }
}
