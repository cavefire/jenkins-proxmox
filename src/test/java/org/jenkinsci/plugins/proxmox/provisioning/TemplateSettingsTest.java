package org.jenkinsci.plugins.proxmox.provisioning;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;

import java.util.Arrays;
import org.junit.Test;

public class TemplateSettingsTest {

    private static final TemplateSettings DEFAULTS =
            new TemplateSettings("linux-ssh", "/home/jenkins/agent", null, null, 22, null);

    @Test
    public void defaultsWithoutNotes() {
        TemplateSettings s = TemplateSettings.parse(null, DEFAULTS);
        assertThat(s.getCredentialsId(), is("linux-ssh"));
        assertThat(s.getRemoteFS(), is("/home/jenkins/agent"));
        assertThat(s.getJavaPath(), nullValue());
        assertThat(s.getSshPort(), is(22));
    }

    @Test
    public void windowsNotesOverrideDefaults() {
        String notes = "Windows 11 build template\n\n"
                + "jenkins-credentials: windows-ssh\n"
                + "jenkins-remote-fs: C:\\jenkins\n"
                + "jenkins-java: C:\\Program Files\\Java\\jdk-11\\bin\\java.exe\n"
                + "jenkins-jvm-options: -Xmx2g -Dfile.encoding=UTF-8\n"
                + "jenkins-ssh-port: 2222\n"
                + "jenkins-network: 10.10.10.0/24\n";
        TemplateSettings s = TemplateSettings.parse(notes, DEFAULTS);
        assertThat(s.getCredentialsId(), is("windows-ssh"));
        assertThat(s.getRemoteFS(), is("C:\\jenkins"));
        assertThat(s.getJavaPath(), is("C:\\Program Files\\Java\\jdk-11\\bin\\java.exe"));
        assertThat(s.getJvmOptions(), is("-Xmx2g -Dfile.encoding=UTF-8"));
        assertThat(s.getSshPort(), is(2222));
        assertThat(s.getNetwork(), is("10.10.10.0/24"));
    }

    @Test
    public void invalidValuesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> TemplateSettings.parse("jenkins-ssh-port: x", DEFAULTS));
        assertThrows(IllegalArgumentException.class, () -> TemplateSettings.parse("jenkins-network: 10.1/99", DEFAULTS));
    }

    @Test
    public void addressSelection() {
        assertThat(TemplateSettings.parse(null, DEFAULTS).pickAddress(Arrays.asList("192.168.1.5", "10.10.10.7")),
                is("192.168.1.5"));
        TemplateSettings s = TemplateSettings.parse("jenkins-network: 10.10.10.0/24", DEFAULTS);
        assertThat(s.pickAddress(Arrays.asList("192.168.1.5", "10.10.10.7")), is("10.10.10.7"));
        assertThat(s.pickAddress(Arrays.asList("192.168.1.5")), nullValue());
        assertThat(TemplateSettings.parse("jenkins-network: 10.10.10.7", DEFAULTS)
                .pickAddress(Arrays.asList("10.10.10.6", "10.10.10.7")), is("10.10.10.7"));
    }
}
