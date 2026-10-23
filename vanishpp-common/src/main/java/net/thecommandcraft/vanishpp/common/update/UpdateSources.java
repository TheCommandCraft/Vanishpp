package net.thecommandcraft.vanishpp.common.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;

/**
 * Where Vanish++ looks for updates, shared by the Paper and Velocity update checkers.
 *
 * <p>Modrinth is asked by project ID (slugs can be renamed), redirects are followed, and when
 * Modrinth can't be reached the checkers fall back to the latest GitHub release. Up to 1.1.8 a
 * renamed slug silently stopped every update notification - none of these may change without
 * the update-health workflow (.github/workflows/update-health.yml) still passing.
 */
public final class UpdateSources {

    /** Never changes, unlike the slug. */
    public static final String MODRINTH_PROJECT_ID = "kbKpK1bc";
    public static final String MODRINTH_PAGE = "https://modrinth.com/plugin/vanish-plus-plus";
    public static final String GITHUB_LATEST_RELEASE =
            "https://api.github.com/repos/TheCommandCraft/Vanishpp/releases/latest";

    private static final int MAX_REDIRECTS = 5;

    private UpdateSources() {
    }

    /** Modrinth API URL listing the project's versions for one loader (paper, velocity, ...). */
    public static String modrinthVersionsUrl(String loader) {
        return "https://api.modrinth.com/v2/project/" + MODRINTH_PROJECT_ID + "/version?loaders=%5B%22" + loader + "%22%5D";
    }

    /** Download page for an exact Modrinth version, or the project page if the version ID is unknown. */
    public static String downloadPage(String modrinthVersionId) {
        return modrinthVersionId != null ? MODRINTH_PAGE + "/version/" + modrinthVersionId : MODRINTH_PAGE;
    }

    /**
     * Opens a GET connection and returns it after the response code is known, following up to
     * {@value #MAX_REDIRECTS} HTTPS redirects - HttpURLConnection doesn't follow 307/308 on its own.
     */
    public static HttpURLConnection open(String url, String userAgent) throws IOException {
        URI current = URI.create(url);
        for (int redirects = 0; ; redirects++) {
            HttpURLConnection connection = (HttpURLConnection) current.toURL().openConnection();
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10_000);
            connection.setReadTimeout(10_000);
            connection.setRequestProperty("User-Agent", userAgent);
            int code = connection.getResponseCode();
            String location = connection.getHeaderField("Location");
            if (code < 300 || code > 399 || code == 304 || location == null)
                return connection;
            connection.disconnect();
            URI next;
            try {
                next = current.resolve(location);
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid redirect location: " + location, e);
            }
            if (redirects >= MAX_REDIRECTS || !"https".equals(next.getScheme()))
                throw new IOException("Unsafe or too many redirects (HTTP " + code + " to " + next + ")");
            current = next;
        }
    }

    /** Latest non-prerelease version on GitHub without the leading "v", or null if there is none. */
    public static String fetchGithubLatestVersion(String userAgent) throws IOException {
        HttpURLConnection connection = open(GITHUB_LATEST_RELEASE, userAgent);
        try {
            if (connection.getResponseCode() != 200)
                throw new IOException("GitHub returned HTTP " + connection.getResponseCode());
            try (InputStreamReader reader = new InputStreamReader(connection.getInputStream())) {
                JsonElement tag = JsonParser.parseReader(reader).getAsJsonObject().get("tag_name");
                if (tag == null || tag.isJsonNull())
                    return null;
                String version = tag.getAsString();
                return version.startsWith("v") ? version.substring(1) : version;
            }
        } catch (IllegalStateException e) {
            throw new IOException("Unexpected GitHub response", e);
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Compares two version strings numerically part by part (1.1.10 > 1.1.9, 1.1.9.1 > 1.1.9).
     * Everything but digits and dots is ignored, so suffixes like -beta compare equal.
     *
     * @return negative if {@code a} is older than {@code b}, 0 if equal, positive if newer
     */
    public static int compareVersions(String a, String b) {
        String[] pa = a.replaceAll("[^0-9.]", "").split("\\.");
        String[] pb = b.replaceAll("[^0-9.]", "").split("\\.");
        for (int i = 0; i < Math.max(pa.length, pb.length); i++) {
            int va = part(pa, i);
            int vb = part(pb, i);
            if (va != vb)
                return Integer.compare(va, vb);
        }
        return 0;
    }

    private static int part(String[] parts, int i) {
        if (i >= parts.length || parts[i].isEmpty())
            return 0;
        try {
            return Integer.parseInt(parts[i]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
