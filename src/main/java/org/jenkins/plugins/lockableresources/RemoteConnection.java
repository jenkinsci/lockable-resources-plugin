/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources;

import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.util.FormValidation;
import java.util.Locale;
import java.util.Objects;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

/**
 * Remote Jenkins connection settings.
 */
public class RemoteConnection extends AbstractDescribableImpl<RemoteConnection> {

    private final String serverId;
    private final String url;
    private final String credentialsId;

    @DataBoundConstructor
    public RemoteConnection(String serverId, String url, String credentialsId) {
        this.serverId = serverId;
        this.url = url;
        this.credentialsId = credentialsId;
    }

    public String getServerId() {
        return serverId;
    }

    public String getUrl() {
        return url;
    }

    public String getCredentialsId() {
        return credentialsId;
    }

    public void validate() {
        if (serverId == null || serverId.isEmpty()) {
            throw new IllegalArgumentException("serverId must not be null or empty");
        }
        if (url == null || url.isEmpty()) {
            throw new IllegalArgumentException("url must not be null or empty");
        }
        if (!isHttpUrl(url)) {
            // The remote base URL is used by the HTTP transport (RemoteApiClient); reject
            // non-http(s) schemes (e.g. file:, ftp:) up front instead of failing opaquely at lock() time.
            throw new IllegalArgumentException("url must be an http:// or https:// URL: " + url);
        }
    }

    /** A remote base URL must be an absolute http(s) URL (network-bridge transport requirement). */
    private static boolean isHttpUrl(String value) {
        if (value == null) {
            return false;
        }
        String v = value.trim().toLowerCase(Locale.ENGLISH);
        return v.startsWith("http://") || v.startsWith("https://");
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        RemoteConnection that = (RemoteConnection) o;
        return Objects.equals(serverId, that.serverId)
                && Objects.equals(url, that.url)
                && Objects.equals(credentialsId, that.credentialsId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serverId, url, credentialsId);
    }

    @Override
    public String toString() {
        return "RemoteConnection{" + "serverId='" + serverId + '\'' + ", url='" + url + '\'' + "}";
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<RemoteConnection> {

        /** Live validation that the remote base URL is an http(s) URL. */
        public FormValidation doCheckUrl(@QueryParameter String value) {
            String trimmed = Util.fixEmptyAndTrim(value);
            if (trimmed == null) {
                return FormValidation.error("URL must not be empty");
            }
            if (!isHttpUrl(trimmed)) {
                return FormValidation.error("URL must start with http:// or https://");
            }
            return FormValidation.ok();
        }
    }
}
