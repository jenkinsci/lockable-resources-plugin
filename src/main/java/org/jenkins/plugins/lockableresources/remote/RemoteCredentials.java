/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.remote;

import com.cloudbees.plugins.credentials.CredentialsProvider;
import com.cloudbees.plugins.credentials.SystemCredentialsProvider;
import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;
import com.cloudbees.plugins.credentials.domains.Domain;
import com.cloudbees.plugins.credentials.domains.DomainRequirement;
import hudson.AbortException;
import hudson.model.Run;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.jenkins.plugins.lockableresources.RemoteConnection;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Resolves the HTTP {@code Authorization} header for a remote connection from its configured
 * username/password credentials (Basic auth). Credentials are looked up first in the {@link Run} context
 * (so per-folder credentials work), then in the system (global) store.
 */
@Restricted(NoExternalUse.class)
public final class RemoteCredentials {

    private static final Logger LOGGER = Logger.getLogger(RemoteCredentials.class.getName());

    private RemoteCredentials() {}

    /**
     * @return a {@code "Basic <base64>"} header value, or {@code ""} when the connection has no
     *     {@code credentialsId}. Fails the build (via {@link AbortException}) when the referenced
     *     credentials cannot be found.
     */
    public static String basicAuthHeader(RemoteConnection remote, Run<?, ?> run) throws AbortException {
        String credentialsId = remote.getCredentialsId();
        if (credentialsId == null || credentialsId.trim().isEmpty()) {
            return "";
        }

        String normalizedCredentialsId = credentialsId.trim();
        StandardUsernamePasswordCredentials credentials = null;
        try {
            if (run != null) {
                credentials = CredentialsProvider.findCredentialById(
                        normalizedCredentialsId,
                        StandardUsernamePasswordCredentials.class,
                        run,
                        Collections.<DomainRequirement>emptyList());
            }
        } catch (Exception ex) {
            LOGGER.log(
                    Level.FINE,
                    "Unable to resolve credentials by Run context for serverId={0}, credentialsId={1}: {2}",
                    new Object[] {remote.getServerId(), normalizedCredentialsId, ex.getMessage()});
        }
        if (credentials == null) {
            credentials = SystemCredentialsProvider.getInstance()
                    .getDomainCredentialsMap()
                    .getOrDefault(Domain.global(), Collections.emptyList())
                    .stream()
                    .filter(StandardUsernamePasswordCredentials.class::isInstance)
                    .map(StandardUsernamePasswordCredentials.class::cast)
                    .filter(candidate -> normalizedCredentialsId.equals(candidate.getId()))
                    .findFirst()
                    .orElse(null);
        }
        if (credentials == null) {
            throw new AbortException(
                    "Remote credentials not found for serverId=" + remote.getServerId() + ", credentialsId="
                            + normalizedCredentialsId);
        }

        String basicToken = credentials.getUsername() + ':' + credentials.getPassword().getPlainText();
        return "Basic " + Base64.getEncoder().encodeToString(basicToken.getBytes(StandardCharsets.UTF_8));
    }
}
