package com.vcfcf.adapters.example;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.regex.Pattern;

/**
 * Typed configuration POJO for the example adapter.
 *
 * <p>Populated from the adapter-instance {@code ResourceConfig} in
 * {@link ExampleAdapter#configureAdapter}. Immutable: every field is
 * {@code final} and set once in the constructor.
 *
 * <p><b>Credential redaction:</b> the password is a secret. It is held in a
 * private field and read, through the package-private {@link #password()},
 * only by the HTTP auth strategy in {@link ExampleAdapter#buildHttpClient}
 * (issue #15). {@link #toString()} prints {@code password=***}. It MUST NOT appear in any log line,
 * exception message, or URL. Search this repo for the marker
 * {@code // REDACT-SECRET} to audit every place a secret is handled.
 *
 * <p><b>Validated on construction (issue #16).</b> The host must be a bare
 * hostname, an IPv4 address or a bracketed IPv6 address, and the port a
 * number from 1 to 65535; anything else throws an
 * {@link IllegalArgumentException} whose message names the field, which
 * Test Connection shows as is. So the base URL is always the target the
 * operator meant, never a scheme, path or user smuggled in through the host.
 */
public final class ExampleConfig {

    /** Target host / IP of the remote system (instance identifier), trimmed. */
    public final String host;

    /** HTTPS port (instance identifier; defaults to 443), 1 to 65535. */
    public final int port;

    /** API username (credential field). */
    public final String username;

    /** API password (credential field). Private: never log it. // REDACT-SECRET */
    private final String password;

    /** Lab opt-out: skip TLS verification for self-signed certs. */
    public final boolean allowInsecure;

    private final String baseUrl;

    /** Dot-separated DNS labels (letters, digits, inner hyphens; 63 per label, 253 total). */
    private static final Pattern HOSTNAME = Pattern.compile(
            "(?=.{1,253}$)[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?"
            + "(\\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*");

    /** Dotted quad shape; each part is range-checked separately. */
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");

    /** Bracketed IPv6 literal (hex groups, colons, optional dotted tail). */
    private static final Pattern IPV6 = Pattern.compile("\\[[0-9A-Fa-f:.]*:[0-9A-Fa-f:.]*\\]");

    /**
     * @throws IllegalArgumentException when the host or port is invalid; the
     *         message names the field and carries no credential
     */
    public ExampleConfig(String host, String port, String username,
            String password, String allowInsecure) {
        this.host = validHost(host);
        this.port = validPort(port);
        this.baseUrl = buildBaseUrl(this.host, this.port);
        this.username = username;
        this.password = password; // REDACT-SECRET
        this.allowInsecure = parseAllowInsecure(allowInsecure);
    }

    /**
     * Strict parse (issue #8): only the exact value {@code "true"} (any
     * letter case, no surrounding spaces) turns certificate validation off.
     * Anything else, including {@code null}, blank, {@code "yes"} or
     * {@code " true"}, validates. A missing or mistyped value must never
     * fall through to insecure, because the connection carries credentials.
     */
    static boolean parseAllowInsecure(String raw) {
        return "true".equalsIgnoreCase(raw);
    }

    /**
     * The WARN logged on every configure while certificate validation is
     * off, or {@code null} when it is on. Address only, no credential.
     */
    String insecureWarning() {
        if (!allowInsecure) return null;
        return "Allow Insecure SSL is true for " + host + ":" + port
                + ": the target certificate is not validated, so the credentials "
                + "go to whatever answers at that address. Set Allow Insecure SSL "
                + "to false unless this is a lab target you accept that for.";
    }

    /**
     * The password, for the auth strategy only. Package-private on purpose:
     * {@code ExampleAdapter.buildHttpClient} is its one caller. // REDACT-SECRET
     */
    String password() {
        return password;
    }

    /** Safe to log: the password is always {@code ***}. // REDACT-SECRET */
    @Override
    public String toString() {
        return "ExampleConfig{host=" + host + ", port=" + port + ", username="
                + username + ", allowInsecure=" + allowInsecure + ", password=***}";
    }

    /** Base URL for the remote API. Carries no secret, safe to log. */
    public String baseUrl() {
        return baseUrl;
    }

    static String validHost(String raw) {
        String h = raw == null ? "" : raw.trim();
        if (h.isEmpty()) {
            throw new IllegalArgumentException("Host / IP Address is required");
        }
        if (h.startsWith("[")) {
            if (!IPV6.matcher(h).matches()) {
                throw new IllegalArgumentException("Host / IP Address '" + h
                        + "' is not a valid bracketed IPv6 address, for example "
                        + "[fd00::10]");
            }
            return h;
        }
        if (h.matches(".*[/@?#:\\s].*")) {
            throw new IllegalArgumentException("Host / IP Address '" + h
                    + "' must be a bare hostname or IP address: no scheme, path, "
                    + "user, port or spaces (put the port in the Port field, and "
                    + "an IPv6 address in brackets)");
        }
        if (IPV4.matcher(h).matches()) {
            for (String part : h.split("\\.")) {
                if (Integer.parseInt(part) > 255) {
                    throw new IllegalArgumentException("Host / IP Address '" + h
                            + "' is not a valid IPv4 address");
                }
            }
            return h;
        }
        if (!HOSTNAME.matcher(h).matches()) {
            throw new IllegalArgumentException("Host / IP Address '" + h
                    + "' is not a valid hostname (letters, digits, hyphens and dots)");
        }
        return h;
    }

    static int validPort(String raw) {
        String p = raw == null ? "" : raw.trim();
        if (p.isEmpty()) return 443;
        int n;
        try {
            n = Integer.parseInt(p);
        } catch (NumberFormatException e) {
            n = -1;
        }
        if (n < 1 || n > 65535) {
            throw new IllegalArgumentException("Port '" + p
                    + "' must be a number from 1 to 65535");
        }
        return n;
    }

    private static String buildBaseUrl(String host, int port) {
        try {
            return new URI("https", null, host, port, null, null, null).toString();
        } catch (URISyntaxException e) {
            throw new IllegalArgumentException("Host / IP Address '" + host
                    + "' does not form a valid URL: " + e.getReason());
        }
    }
}
