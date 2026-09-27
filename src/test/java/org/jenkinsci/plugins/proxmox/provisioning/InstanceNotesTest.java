package org.jenkinsci.plugins.proxmox.provisioning;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

import org.junit.Test;

public class InstanceNotesTest {

    private static final String ID = "abc123";
    private static final String NODE = "https://jenkins.example/computer/jenkins-120-ubuntu/";
    private static final String BUILD = "https://jenkins.example/job/folder/job/app/42/";

    @Test
    public void roundTrip() {
        String notes = InstanceNotes.render(ID, NODE, BUILD);
        assertThat(InstanceNotes.parse(notes).get(InstanceNotes.INSTANCE_ID), is(ID));
        assertThat(InstanceNotes.parse(notes).get(InstanceNotes.NODE), is(NODE));
        assertThat(InstanceNotes.parse(notes).get(InstanceNotes.BUILD), is(BUILD));
    }

    @Test
    public void deletionAllowedWhenBuildMatches() {
        assertThat(InstanceNotes.deletionBlocker(InstanceNotes.render(ID, NODE, BUILD), ID, NODE, BUILD), nullValue());
    }

    @Test
    public void deletionAllowedWithoutBuildWhenNoneRan() {
        assertThat(InstanceNotes.deletionBlocker(InstanceNotes.render(ID, NODE, null), ID, NODE, null), nullValue());
    }

    @Test
    public void deletionRefusedForOtherBuild() {
        String notes = InstanceNotes.render(ID, NODE, "https://jenkins.example/job/other/1/");
        assertThat(InstanceNotes.deletionBlocker(notes, ID, NODE, BUILD), containsString("job/other/1"));
    }

    @Test
    public void deletionRefusedWhenBuildMissingFromNotes() {
        assertThat(
                InstanceNotes.deletionBlocker(InstanceNotes.render(ID, NODE, null), ID, NODE, BUILD),
                containsString("instead of " + BUILD));
    }

    @Test
    public void deletionRefusedForOtherJenkinsOrNode() {
        String notes = InstanceNotes.render(ID, NODE, BUILD);
        assertThat(InstanceNotes.deletionBlocker(notes, "other", NODE, BUILD), containsString("Jenkins instance"));
        assertThat(InstanceNotes.deletionBlocker(notes, ID, NODE + "x/", BUILD), containsString("node"));
    }

    @Test
    public void deletionRefusedForEditedNotes() {
        assertThat(InstanceNotes.deletionBlocker("my notes", ID, NODE, null), containsString("Jenkins instance"));
        assertThat(InstanceNotes.deletionBlocker(null, ID, NODE, null), containsString("Jenkins instance"));
    }

    @Test
    public void parseToleratesMarkdownVariants() {
        String notes = "text\r\n* jenkins-node:   " + NODE + "  \r\njenkins-build: " + BUILD + "\njenkins-empty:";
        assertThat(InstanceNotes.parse(notes).get(InstanceNotes.NODE), is(NODE));
        assertThat(InstanceNotes.parse(notes).get(InstanceNotes.BUILD), is(BUILD));
        assertThat(InstanceNotes.parse(notes).containsKey("jenkins-empty"), is(false));
    }
}
