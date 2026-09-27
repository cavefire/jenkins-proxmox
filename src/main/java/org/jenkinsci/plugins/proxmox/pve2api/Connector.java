package org.jenkinsci.plugins.proxmox.pve2api;

import hudson.util.Secret;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URLEncoder;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.security.auth.login.LoginException;
import kong.unirest.HttpRequest;
import kong.unirest.HttpResponse;
import kong.unirest.JsonNode;
import kong.unirest.Unirest;
import kong.unirest.UnirestException;
import kong.unirest.UnirestInstance;
import kong.unirest.json.JSONArray;
import kong.unirest.json.JSONObject;
import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.conn.DnsResolver;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.ssl.NoopHostnameVerifier;
import org.apache.http.conn.ssl.SSLConnectionSocketFactory;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.protocol.HttpContext;
import org.apache.http.ssl.SSLContexts;

public class Connector {

    public static final long WAIT_TIME_MS = 1000;
    public static final int DEFAULT_API_PORT = 8006;

    /** Routes connections to Proxmox hosts, e.g. through SSH to the host. */
    public interface Tunnel {
        /** @return the local address that reaches {@code host:port} */
        InetSocketAddress route(String host, int port) throws IOException;
    }

    /** A Proxmox API endpoint. */
    public static final class Host {
        public final String name;
        public final int port;

        Host(String name, int port) {
            this.name = name;
            this.port = port;
        }

        @Override
        public String toString() {
            return port == DEFAULT_API_PORT ? name : name + ":" + port;
        }
    }

    protected String username;
    protected String realm;
    protected Secret password;

    private final List<Host> hosts;
    /** Index of the host that answered last; requests go there first. */
    private volatile int current;

    private volatile String authTicket;
    private Date authTicketIssuedTimestamp;
    private volatile String csrfPreventionToken;
    private UnirestInstance unirest;

    private static final Logger LOGGER = Logger.getLogger(Connector.class.getName());

    public Connector(String hostname, String username, String realm, Secret password) {
        this(hostname, username, realm, password, false);
    }

    public Connector(String hostname, String username, String realm, Secret password, Boolean ignoreSSL) {
        this(hostname, username, realm, password, ignoreSSL, null);
    }

    /**
     * @param hostnames one or more API hosts of the cluster ({@code host[:port]}, separated by commas or spaces);
     *     requests fail over to the next host when one cannot be reached
     * @param tunnel routes all connections, or null to connect directly
     */
    public Connector(
            String hostnames, String username, String realm, Secret password, Boolean ignoreSSL, Tunnel tunnel) {
        this.hosts = parseHosts(hostnames);
        this.username = username;
        this.realm = realm;
        this.password = password;
        boolean insecure = ignoreSSL != null && ignoreSSL;

        this.unirest = Unirest.spawnInstance();
        if (tunnel == null) {
            unirest.config().verifySsl(!insecure).reset();
        } else {
            unirest.config().httpClient(tunneledClient(tunnel, insecure));
        }
        this.authTicketIssuedTimestamp = null;
    }

    /** Parses {@code host[:port]} entries separated by commas or whitespace. */
    public static List<Host> parseHosts(String hostnames) {
        List<Host> result = new ArrayList<>();
        for (String entry : (hostnames == null ? "" : hostnames).trim().split("[,;\\s]+")) {
            if (entry.isEmpty()) {
                continue;
            }
            String name = entry;
            int port = DEFAULT_API_PORT;
            try {
                URI uri = new URI("https://" + entry);
                if (uri.getHost() != null) {
                    name = uri.getHost();
                    port = uri.getPort() != -1 ? uri.getPort() : DEFAULT_API_PORT;
                }
            } catch (URISyntaxException e) {
                LOGGER.log(Level.FINE, "Unparsable host " + entry, e);
            }
            result.add(new Host(name, port));
        }
        if (result.isEmpty()) {
            throw new IllegalArgumentException("No Proxmox host given");
        }
        return result;
    }

    public List<Host> getHosts() {
        return Collections.unmodifiableList(hosts);
    }

    /**
     * HTTP client that opens every connection through the tunnel. TLS still verifies the certificate against the
     * real host name (unless SSL checks are disabled).
     */
    private static CloseableHttpClient tunneledClient(Tunnel tunnel, boolean insecure) {
        SSLContext context;
        try {
            context = insecure
                    ? SSLContexts.custom()
                            .loadTrustMaterial(null, (chain, authType) -> true)
                            .build()
                    : SSLContexts.createDefault();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        HostnameVerifier verifier =
                insecure ? NoopHostnameVerifier.INSTANCE : SSLConnectionSocketFactory.getDefaultHostnameVerifier();
        SSLConnectionSocketFactory ssl = new SSLConnectionSocketFactory(context, verifier) {
            @Override
            public Socket connectSocket(
                    int timeout,
                    Socket socket,
                    HttpHost host,
                    InetSocketAddress remoteAddress,
                    InetSocketAddress localAddress,
                    HttpContext httpContext)
                    throws IOException {
                InetSocketAddress routed = tunnel.route(host.getHostName(), host.getPort());
                return super.connectSocket(timeout, socket, host, routed, localAddress, httpContext);
            }
        };
        Registry<ConnectionSocketFactory> registry =
                RegistryBuilder.<ConnectionSocketFactory>create().register("https", ssl).build();
        // Host names are resolved by the far end of the tunnel, not here.
        DnsResolver resolver = host -> new InetAddress[] {InetAddress.getLoopbackAddress()};
        PoolingHttpClientConnectionManager manager =
                new PoolingHttpClientConnectionManager(registry, null, null, resolver, 60, TimeUnit.SECONDS);
        return HttpClients.custom()
                .setConnectionManager(manager)
                .setDefaultRequestConfig(RequestConfig.custom()
                        .setConnectTimeout(15_000)
                        .setSocketTimeout(60_000)
                        .build())
                .build();
    }

    private static String baseUrl(Host host) {
        return "https://" + host.name + ":" + host.port + "/api2/json/";
    }

    private interface HostCall<T> {
        T call(String baseUrl);
    }

    /** Runs a request against the hosts in turn until one can be reached. */
    private <T> T onAnyHost(HostCall<T> call) {
        RuntimeException last = null;
        int start = current;
        for (int i = 0; i < hosts.size(); i++) {
            int index = (start + i) % hosts.size();
            try {
                T result = call.call(baseUrl(hosts.get(index)));
                current = index;
                return result;
            } catch (UnirestException e) {
                LOGGER.log(Level.FINE, "Proxmox host " + hosts.get(index) + " not reachable", e);
                if (last != null) {
                    e.addSuppressed(last);
                }
                last = e;
            }
        }
        throw last;
    }

    public synchronized void login() throws LoginException {
        JSONObject authTickets = onAnyHost(base -> unirest.post(base + "access/ticket")
                .field("username", username + "@" + realm)
                .field("password", password.getPlainText())
                .asJson()
                .getBody()
                .getObject());
        try {
            JSONObject data = authTickets.getJSONObject("data");
            authTicket = data.get("ticket").toString();
            csrfPreventionToken = data.get("CSRFPreventionToken").toString();
            authTicketIssuedTimestamp = new Date();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed reading JSON response", e);
            throw new LoginException("Failed reading JSON response");
        }
    }

    public synchronized void checkIfAuthTicketIsValid() throws LoginException {
        // Authentication ticket has a lifetime of 2 hours, so login again when it expires
        if (authTicketIssuedTimestamp == null
                || authTicketIssuedTimestamp.getTime() <= (new Date().getTime() - (120 * 60 * 1000))) {
            login();
        }
    }

    @SuppressWarnings("rawtypes")
    private HttpRequest request(String baseUrl, String method, String path, String body) {
        String url = baseUrl + path;
        HttpRequest req;
        switch (method) {
            case "GET":
                req = unirest.get(url);
                break;
            case "DELETE":
                req = unirest.delete(url);
                break;
            case "POST":
                req = unirest.post(url)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .body(body == null ? "" : body);
                break;
            case "PUT":
                req = unirest.put(url)
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .body(body == null ? "" : body);
                break;
            default:
                throw new IllegalArgumentException(method);
        }
        return req.header("Cookie", "PVEAuthCookie=" + authTicket).header("CSRFPreventionToken", csrfPreventionToken);
    }

    /** Sends an authenticated request, failing over between hosts. */
    private HttpResponse<JsonNode> send(String method, String path, String body) throws LoginException {
        checkIfAuthTicketIsValid();
        return onAnyHost(base -> request(base, method, path, body).asJson());
    }

    private JsonNode getJSONResource(String apiUrl) throws LoginException {
        return send("GET", apiUrl, null).getBody();
    }

    private JsonNode postJSONResource(String apiUrl, String body) throws LoginException {
        return send("POST", apiUrl, body).getBody();
    }

    public List<String> getNodes() throws LoginException {
        List<String> res = new ArrayList<String>();
        JSONArray nodes = getJSONResource("nodes").getObject().getJSONArray("data");
        for (int i = 0; i < nodes.length(); i++) {
            res.add(nodes.getJSONObject(i).getString("node"));
        }
        return res;
    }

    public JSONObject getTaskStatus(String node, String taskId) throws LoginException {
        JsonNode response = getJSONResource("nodes/" + node + "/tasks/" + taskId + "/status");
        return response.getObject().getJSONObject("data");
    }

    public JSONObject getQemuMachineStatus(String node, Integer vmid) throws LoginException {
        JsonNode response = getJSONResource("nodes/" + node + "/qemu/" + vmid + "/status/current");
        return response.getObject().getJSONObject("data");
    }

    public Boolean isQemuMachineRunning(String node, Integer vmid) throws LoginException {
        JSONObject QemuMachineStatus = null;
        Boolean isRunning = true;
        QemuMachineStatus = getQemuMachineStatus(node, vmid);
        isRunning = (QemuMachineStatus.getString("status").equals("running"));
        return isRunning;
    }

    public JSONObject waitForTaskToFinish(String node, String taskId) throws LoginException, InterruptedException {
        JSONObject lastTaskStatus = null;
        Boolean isRunning = true;
        while (isRunning) {
            lastTaskStatus = getTaskStatus(node, taskId);
            isRunning = (lastTaskStatus.getString("status").equals("running"));
            if (isRunning) {
                Thread.sleep(WAIT_TIME_MS);
            }
        }
        return lastTaskStatus;
    }

    public HashMap<String, Integer> getQemuMachines(String node) throws LoginException {
        HashMap<String, Integer> res = new HashMap<String, Integer>();
        JSONArray qemuVMs =
                getJSONResource("nodes/" + node + "/qemu").getObject().getJSONArray("data");
        for (int i = 0; i < qemuVMs.length(); i++) {
            JSONObject vm = qemuVMs.getJSONObject(i);
            res.put(vm.getString("name"), vm.getInt("vmid"));
        }
        return res;
    }

    public List<String> getQemuMachineSnapshots(String node, Integer vmid) throws LoginException {
        List<String> res = new ArrayList<String>();
        JSONArray snapshots = getJSONResource("nodes/" + node + "/qemu/" + vmid.toString() + "/snapshot")
                .getObject()
                .getJSONArray("data");
        for (int i = 0; i < snapshots.length(); i++) {
            res.add(snapshots.getJSONObject(i).getString("name"));
        }
        return res;
    }

    public String rollbackQemuMachineSnapshot(String node, Integer vmid, String snapshotName) throws LoginException {
        return postJSONResource(
                        "nodes/" + node + "/qemu/" + vmid.toString() + "/snapshot/" + snapshotName + "/rollback", "")
                .getObject()
                .getString("data");
    }

    public String startQemuMachine(String node, Integer vmid) throws LoginException {
        return postJSONResource("nodes/" + node + "/qemu/" + vmid.toString() + "/status/start", "")
                .getObject()
                .getString("data");
    }

    public String stopQemuMachine(String node, Integer vmid) throws LoginException {
        return postJSONResource("nodes/" + node + "/qemu/" + vmid.toString() + "/status/stop", "")
                .getObject()
                .getString("data");
    }

    public String shutdownQemuMachine(String node, Integer vmid) throws LoginException {
        return postJSONResource("nodes/" + node + "/qemu/" + vmid.toString() + "/status/shutdown", "")
                .getObject()
                .getString("data");
    }

    /*
     * Generic API access used by template provisioning. Unlike the methods above, these
     * check the HTTP status and report Proxmox's error message.
     */

    static String encodeParams(Map<String, ?> params) {
        StringBuilder sb = new StringBuilder();
        if (params == null) {
            return "";
        }
        for (Map.Entry<String, ?> e : params.entrySet()) {
            if (e.getValue() == null) {
                continue;
            }
            Collection<?> values = e.getValue() instanceof Collection
                    ? (Collection<?>) e.getValue()
                    : Collections.singletonList(e.getValue());
            for (Object v : values) {
                if (sb.length() > 0) {
                    sb.append('&');
                }
                sb.append(urlEncode(e.getKey())).append('=').append(urlEncode(String.valueOf(v)));
            }
        }
        return sb.toString();
    }

    private static String urlEncode(String s) {
        try {
            return URLEncoder.encode(s, "UTF-8");
        } catch (UnsupportedEncodingException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Builds a parameter map from alternating keys and values. */
    public static Map<String, Object> params(Object... keysAndValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) {
            map.put((String) keysAndValues[i], keysAndValues[i + 1]);
        }
        return map;
    }

    private JSONObject call(String method, String path, Map<String, ?> params) throws ProxmoxException {
        try {
            checkIfAuthTicketIsValid();
        } catch (LoginException | RuntimeException e) {
            throw new ProxmoxException("Login to Proxmox failed: " + e.getMessage(), e);
        }
        String encoded = encodeParams(params);
        boolean inUrl = "GET".equals(method) || "DELETE".equals(method);
        String target = inUrl && !encoded.isEmpty() ? path + "?" + encoded : path;
        HttpResponse<JsonNode> response;
        try {
            response = send(method, target, inUrl ? null : encoded);
        } catch (LoginException e) {
            throw new ProxmoxException("Login to Proxmox failed: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new ProxmoxException(method + " " + path + " failed: " + e.getMessage(), e);
        }
        JSONObject body = response.getBody() != null ? response.getBody().getObject() : null;
        if (!response.isSuccess()) {
            String detail = body != null && body.has("errors") ? " " + body.get("errors") : "";
            throw new ProxmoxException(method + " " + path + " failed: HTTP " + response.getStatus() + " "
                    + response.getStatusText() + detail);
        }
        return body != null ? body : new JSONObject();
    }

    private static String guestPath(String node, String type, int vmid) {
        return "nodes/" + node + "/" + type + "/" + vmid;
    }

    /** All guests (QEMU and LXC) in the cluster, as returned by {@code /cluster/resources?type=vm}. */
    public JSONArray getClusterGuests() throws ProxmoxException {
        return call("GET", "cluster/resources", params("type", "vm")).getJSONArray("data");
    }

    /**
     * Lowest free VM ID that is at least {@code first} and not in {@code skip}, considering all guests of the
     * cluster. Proxmox checks the ID again when the guest is created.
     */
    public int nextVmid(int first, Set<Integer> skip) throws ProxmoxException {
        Set<Integer> used = new HashSet<>(skip);
        JSONArray guests = getClusterGuests();
        for (int i = 0; i < guests.length(); i++) {
            used.add(guests.getJSONObject(i).getInt("vmid"));
        }
        return firstFree(used, first);
    }

    public static int firstFree(Set<Integer> used, int first) {
        int id = Math.max(first, 100);
        while (used.contains(id)) {
            id++;
        }
        return id;
    }

    public int nextVmid() throws ProxmoxException {
        return Integer.parseInt(call("GET", "cluster/nextid", null).get("data").toString());
    }

    /**
     * Clones a guest. {@code type} is {@code qemu} or {@code lxc}. {@code target} (optional) creates the clone on
     * another node, which Proxmox only allows for guests on shared storage.
     * @return the UPID of the clone task
     */
    public String cloneGuest(
            String node,
            String type,
            int vmid,
            int newid,
            String name,
            String description,
            boolean full,
            String storage,
            String target)
            throws ProxmoxException {
        Map<String, Object> params = new HashMap<>();
        params.put("newid", newid);
        params.put("target", target);
        params.put("qemu".equals(type) ? "name" : "hostname", name);
        params.put("description", description);
        params.put("full", full ? 1 : 0);
        if (full && storage != null && !storage.isEmpty()) {
            params.put("storage", storage);
        }
        return call("POST", guestPath(node, type, vmid) + "/clone", params).getString("data");
    }

    /** Online state and load of all cluster nodes ({@code /cluster/resources?type=node}). */
    public JSONArray getClusterNodes() throws ProxmoxException {
        return call("GET", "cluster/resources", params("type", "node")).getJSONArray("data");
    }

    /**
     * Asks each configured host which cluster node it is.
     *
     * @return configured host name by node name; hosts that cannot be reached are left out
     */
    public Map<String, String> getNodesOfHosts() throws ProxmoxException {
        try {
            checkIfAuthTicketIsValid();
        } catch (LoginException | RuntimeException e) {
            throw new ProxmoxException("Login to Proxmox failed: " + e.getMessage(), e);
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Host host : hosts) {
            try {
                HttpResponse<JsonNode> response =
                        request(baseUrl(host), "GET", "cluster/status", null).asJson();
                JSONArray status = response.getBody().getObject().getJSONArray("data");
                for (int i = 0; i < status.length(); i++) {
                    JSONObject entry = status.getJSONObject(i);
                    if ("node".equals(entry.optString("type")) && entry.optInt("local", 0) == 1) {
                        result.put(entry.getString("name"), host.name);
                    }
                }
            } catch (RuntimeException e) {
                LOGGER.log(Level.FINE, "Could not ask " + host + " for its node name", e);
            }
        }
        return result;
    }

    /** Cluster membership including node IP addresses ({@code /cluster/status}). */
    public JSONArray getClusterStatus() throws ProxmoxException {
        return call("GET", "cluster/status", null).getJSONArray("data");
    }

    /**
     * Resource mappings of a kind ({@code pci}, {@code usb} or {@code dir}). Empty if the Proxmox version has none
     * of that kind.
     */
    public JSONArray getResourceMappings(String kind) {
        try {
            return call("GET", "cluster/mapping/" + kind, null).getJSONArray("data");
        } catch (ProxmoxException | RuntimeException e) {
            LOGGER.log(Level.FINE, "No " + kind + " resource mappings", e);
            return new JSONArray();
        }
    }

    /** Storages enabled on a node, with their {@code shared} flag. */
    public JSONArray getNodeStorages(String node) throws ProxmoxException {
        return call("GET", "nodes/" + node + "/storage", params("enabled", 1)).getJSONArray("data");
    }

    /** Migrates a stopped guest to another node, including local disks. */
    public String migrateGuest(String node, String type, int vmid, String target) throws ProxmoxException {
        return call("POST", guestPath(node, type, vmid) + "/migrate", params("target", target))
                .getString("data");
    }

    public JSONObject getGuestConfig(String node, String type, int vmid) throws ProxmoxException {
        return call("GET", guestPath(node, type, vmid) + "/config", null).getJSONObject("data");
    }

    public void updateGuestConfig(String node, String type, int vmid, Map<String, ?> params) throws ProxmoxException {
        call("PUT", guestPath(node, type, vmid) + "/config", params);
    }

    public JSONObject getGuestStatus(String node, String type, int vmid) throws ProxmoxException {
        return call("GET", guestPath(node, type, vmid) + "/status/current", null)
                .getJSONObject("data");
    }

    public String startGuest(String node, String type, int vmid) throws ProxmoxException {
        return call("POST", guestPath(node, type, vmid) + "/status/start", null).getString("data");
    }

    public String stopGuest(String node, String type, int vmid) throws ProxmoxException {
        return call("POST", guestPath(node, type, vmid) + "/status/stop", null).getString("data");
    }

    /** Destroys a guest including all its disks. */
    public String destroyGuest(String node, String type, int vmid) throws ProxmoxException {
        return call("DELETE", guestPath(node, type, vmid), params("purge", 1, "destroy-unreferenced-disks", 1))
                .getString("data");
    }

    /**
     * IPv4 addresses of a running guest: from the container's interfaces for LXC, from the QEMU guest agent for
     * VMs. Loopback and link-local addresses are left out.
     *
     * @return the addresses, empty if none are known yet (e.g. the guest agent is not running yet)
     */
    public List<String> getGuestAddresses(String node, String type, int vmid) {
        List<String> result = new ArrayList<>();
        try {
            if ("lxc".equals(type)) {
                JSONArray interfaces = call("GET", guestPath(node, type, vmid) + "/interfaces", null)
                        .getJSONArray("data");
                for (int i = 0; i < interfaces.length(); i++) {
                    String inet = interfaces.getJSONObject(i).optString("inet", "");
                    addAddress(result, inet.contains("/") ? inet.substring(0, inet.indexOf('/')) : inet);
                }
            } else {
                JSONArray interfaces = call(
                                "GET", guestPath(node, type, vmid) + "/agent/network-get-interfaces", null)
                        .getJSONObject("data")
                        .getJSONArray("result");
                for (int i = 0; i < interfaces.length(); i++) {
                    JSONArray addresses = interfaces.getJSONObject(i).optJSONArray("ip-addresses");
                    for (int j = 0; addresses != null && j < addresses.length(); j++) {
                        JSONObject address = addresses.getJSONObject(j);
                        if ("ipv4".equals(address.optString("ip-address-type"))) {
                            addAddress(result, address.optString("ip-address", ""));
                        }
                    }
                }
            }
        } catch (ProxmoxException | RuntimeException e) {
            LOGGER.log(Level.FINE, "No addresses for guest " + vmid + " yet", e);
        }
        return result;
    }

    private static void addAddress(List<String> result, String ip) {
        if (!ip.isEmpty() && !ip.startsWith("127.") && !ip.startsWith("169.254.") && !result.contains(ip)) {
            result.add(ip);
        }
    }

    /**
     * Waits for a task and fails unless it finished with exit status OK.
     */
    public JSONObject waitForTaskOk(String node, String upid, long timeoutMillis)
            throws ProxmoxException, InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (true) {
            JSONObject status = call("GET", "nodes/" + node + "/tasks/" + upid + "/status", null)
                    .getJSONObject("data");
            if (!"running".equals(status.optString("status"))) {
                String exit = status.optString("exitstatus");
                if (!"OK".equals(exit)) {
                    throw new ProxmoxException("Task " + upid + " failed: " + exit);
                }
                return status;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new ProxmoxException("Timed out waiting for task " + upid);
            }
            Thread.sleep(WAIT_TIME_MS);
        }
    }

    protected void finalize() {
        unirest.shutDown();
    }
}
