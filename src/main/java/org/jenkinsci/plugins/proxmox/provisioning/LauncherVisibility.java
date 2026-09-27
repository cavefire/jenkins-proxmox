package org.jenkinsci.plugins.proxmox.provisioning;

import hudson.Extension;
import hudson.model.Descriptor;
import hudson.model.DescriptorVisibilityFilter;

/** Keeps the template instance launcher out of the launcher choices for manually created agents. */
@Extension
public class LauncherVisibility extends DescriptorVisibilityFilter {
    @Override
    public boolean filter(Object context, Descriptor descriptor) {
        return !(descriptor instanceof ProxmoxSshLauncher.DescriptorImpl) || context instanceof ProxmoxInstanceAgent;
    }
}
