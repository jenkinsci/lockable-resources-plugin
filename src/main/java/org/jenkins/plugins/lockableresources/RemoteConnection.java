/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources;

import com.cloudbees.plugins.credentials.CredentialsMatchers;
import com.cloudbees.plugins.credentials.common.StandardListBoxModel;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.URIRequirementBuilder;
import hudson.Extension;
import hudson.Util;
import hudson.model.AbstractDescribableImpl;
import hudson.model.Descriptor;
import hudson.security.ACL;
import hudson.util.FormValidation;
import hudson.util.ListBoxModel;
import java.util.Locale;
import java.util.Objects;
import jenkins.model.Jenkins;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;
import org.kohsuke.stapler.verb.POST;

/**
 * Remote Jenkins connection settings.
 */
public class RemoteConnection extends AbstractDescribableImpl<RemoteConnection> {

    private final String serverId;
    private final String url;
    private final String credentialsId;

    /**
     * Whether this controller may use the connection. Turning it off is the borrower's counterpart of
     * the server-side maintenance switch: the entry keeps its URL and credentials binding, but no new
     * lock is requested through it. Leases already held are unaffected - dropping them would strand
     * the resources on the other side.
     *
     * <p>Set through a {@link DataBoundSetter} rather than the constructor so that configuration
     * written before this existed - JCasC yaml with only the three connection fields - still loads.
     */
    private boolean enabled = true;

    @DataBoundConstructor
    public RemoteConnection(String serverId, String url, String credentialsId) {
        this.serverId = serverId;
        this.url = url;
        this.credentialsId = credentialsId;
    }

    @DataBoundSetter
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isEnabled() {
        return enabled;
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
        return enabled == that.enabled
                && Objects.equals(serverId, that.serverId)
                && Objects.equals(url, that.url)
                && Objects.equals(credentialsId, that.credentialsId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(serverId, url, credentialsId, enabled);
    }

    @Override
    public String toString() {
        return "RemoteConnection{" + "serverId='" + serverId + '\'' + ", url='" + url + '\'' + ", enabled=" + enabled
                + "}";
    }

    @Extension
    public static class DescriptorImpl extends Descriptor<RemoteConnection> {

        /**
         * Credentials dropdown for remote API auth (username + API token in password field).
         */
        @POST
        public ListBoxModel doFillCredentialsIdItems(@QueryParameter String credentialsId, @QueryParameter String url) {
            if (!Jenkins.get().hasPermission(Jenkins.ADMINISTER)) {
                return new StandardListBoxModel().includeCurrentValue(credentialsId);
            }

            return new StandardListBoxModel()
                    .includeEmptyValue()
                    .includeMatchingAs(
                            ACL.SYSTEM2,
                            Jenkins.get(),
                            StandardUsernamePasswordCredentials.class,
                            URIRequirementBuilder.fromUri(Util.fixEmptyAndTrim(url))
                                    .build(),
                            CredentialsMatchers.always())
                    .includeCurrentValue(credentialsId);
        }

        /** Live validation that the remote base URL is an http(s) URL. */
        @POST
        public FormValidation doCheckUrl(@QueryParameter String value) {
            Jenkins.get().checkPermission(Jenkins.ADMINISTER);
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
