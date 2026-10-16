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
 *
 * <p><b>Messages never echo a rejected value.</b> An operator may paste
 * {@code https://user:password@host} into the Host field, and these messages
 * are logged on configure, on every read of every cycle, and shown in Test
 * Connection. So no host message ever quotes the host: a value that passes
 * one check can still be a password ({@code Hunter2!pw}), and the operator
 * knows what they typed. Each message names the rule instead. Port is quoted
 * only when it is plain digits. // REDACT-SECRET
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
                // Not quoted: it failed the character check. // REDACT-SECRET
                throw new IllegalArgumentException("Host / IP Address is not a "
                        + "valid bracketed IPv6 address (hex digits and colons "
                        + "inside [ ], for example [fd00::10])");
            }
            return h;
        }
        String broken = brokenHostRule(h);
        if (broken != null) {
            // Not quoted: it failed the character check. // REDACT-SECRET
            throw new IllegalArgumentException("Host / IP Address must be a bare "
                    + "hostname or IP address: " + broken);
        }
        if (IPV4.matcher(h).matches()) {
            for (String part : h.split("\\.")) {
                if (Integer.parseInt(part) > 255) {
                    throw new IllegalArgumentException("Host / IP Address is "
                            + "not a valid IPv4 address (each part 0 to 255)");
                }
            }
            return h;
        }
        if (!HOSTNAME.matcher(h).matches()) {
            throw new IllegalArgumentException("Host / IP Address is not a "
                    + "valid hostname (letters, digits, inner hyphens and dots)");
        }
        // "999" or "10.1" would be read as a shorthand IPv4 literal (0.0.3.231,
        // 10.0.0.1), not the hostname the operator meant.
        String lastLabel = h.substring(h.lastIndexOf('.') + 1);
        if (lastLabel.chars().allMatch(Character::isDigit)) {
            throw new IllegalArgumentException("Host / IP Address is neither a "
                    + "hostname nor a full dotted IPv4 address "
                    + "(a.b.c.d)");
        }
        return h;
    }

    /**
     * Which rule a host outside brackets breaks, in operator words, or
     * {@code null} when it has none of the forbidden characters. Names the
     * rule only, never the value. // REDACT-SECRET
     */
    private static String brokenHostRule(String h) {
        if (h.indexOf('@') >= 0) {
            return "it must not contain a user name or password ('@')";
        }
        if (h.contains("://") || h.indexOf('/') >= 0) {
            return "it must not contain a scheme or path ('https://', '/')";
        }
        if (h.indexOf('?') >= 0 || h.indexOf('#') >= 0) {
            return "it must not contain a query or fragment ('?', '#')";
        }
        if (h.indexOf(':') >= 0) {
            return "it must not contain a port (put it in the Port field) or an "
                    + "unbracketed IPv6 address (write [fd00::10])";
        }
        for (int i = 0; i < h.length(); i++) {
            if (Character.isWhitespace(h.charAt(i))) {
                return "it must not contain spaces";
            }
        }
        return null;
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
            // Quote it only when it is plain digits. // REDACT-SECRET
            String shown = p.matches("\\d{1,10}") ? " '" + p + "'" : "";
            throw new IllegalArgumentException("Port" + shown
                    + " must be a number from 1 to 65535");
        }
        return n;
    }

    private static String buildBaseUrl(String host, int port) {
        try {
            return new URI("https", null, host, port, null, null, null).toString();
        } catch (URISyntaxException e) {
            // Not quoted, like every host message. // REDACT-SECRET
            throw new IllegalArgumentException("Host / IP Address does not form "
                    + "a valid URL");
        }
    }
}
