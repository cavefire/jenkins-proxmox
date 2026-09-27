package org.jenkinsci.plugins.proxmox.provisioning;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;

import kong.unirest.json.JSONObject;
import org.junit.Test;

public class GuestRequirementsTest {

    @Test
    public void mappedDevicesAndStorages() {
        JSONObject config = new JSONObject()
                .put("hostpci0", "mapping=gpu,pcie=1")
                .put("hostpci1", "mapping=nic")
                .put("usb0", "mapping=dongle,usb3=1")
                .put("usb1", "spice")
                .put("virtiofs0", "dirid=share,cache=auto")
                .put("scsi0", "ceph:base-9000-disk-0,size=32G")
                .put("efidisk0", "ceph:base-9000-disk-1,efitype=4m")
                .put("ide2", "local:iso/virtio-win.iso,media=cdrom")
                .put("net0", "virtio=BC:24:11:0D:E8:68,bridge=vmbr1")
                .put("memory", "4096")
                .put("cores", 2)
                .put("sockets", 2);
        GuestRequirements r = GuestRequirements.fromConfig("qemu", config);
        assertThat(r.getMappings(), containsInAnyOrder("pci:gpu", "pci:nic", "usb:dongle", "dir:share"));
        assertThat(r.isPinned(), is(false));
        assertThat(r.getStorages(), contains("ceph"));
        assertThat(r.getMemoryBytes(), is(4096L * 1024 * 1024));
        assertThat(r.getCores(), is(4));
    }

    /** Configuration of a real VM passing through a GPU and a USB device without mappings. */
    @Test
    public void unmappedDevicesPin() {
        JSONObject config = new JSONObject()
                .put("hostpci0", "0000:05:00,pcie=1")
                .put("usb0", "host=30f2:0001,usb3=1")
                .put("ide0", "local-lvm:vm-104-disk-1,size=100G")
                .put("tpmstate0", "local-lvm:vm-104-disk-2,size=4M,version=v2.0")
                .put("memory", "6144");
        GuestRequirements r = GuestRequirements.fromConfig("qemu", config);
        assertThat(r.isPinned(), is(true));
        assertThat(r.getMappings(), empty());
        assertThat(r.getStorages(), contains("local-lvm"));
    }

    @Test
    public void containerStorages() {
        JSONObject config = new JSONObject()
                .put("rootfs", "local-lvm:base-9001-disk-0,size=8G")
                .put("mp0", "nfs:subvol-9001-disk-1,mp=/data")
                .put("mp1", "/mnt/host,mp=/host")
                .put("memory", 1024)
                .put("cores", 2);
        GuestRequirements r = GuestRequirements.fromConfig("lxc", config);
        assertThat(r.getStorages(), containsInAnyOrder("local-lvm", "nfs"));
        assertThat(r.isPinned(), is(false));
        assertThat(r.getCores(), is(2));
    }
}
