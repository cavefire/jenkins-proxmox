package org.jenkinsci.plugins.proxmox.pve2api;

import java.io.IOException;

/**
 * A Proxmox API call failed.
 */
public class ProxmoxException extends IOException {

    private static final long serialVersionUID = 1L;

    public ProxmoxException(String message) {
        super(message);
    }

    public ProxmoxException(String message, Throwable cause) {
        super(message, cause);
    }
}
