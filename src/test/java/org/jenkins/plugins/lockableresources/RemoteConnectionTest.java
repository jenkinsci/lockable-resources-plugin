/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import hudson.util.FormValidation;
import org.junit.jupiter.api.Test;

class RemoteConnectionTest {

    @Test
    void testBasicConstruction() {
        RemoteConnection connection = new RemoteConnection("server1", "http://jenkins1.example.com", "creds-1");

        assertEquals("server1", connection.getServerId());
        assertEquals("http://jenkins1.example.com", connection.getUrl());
        assertEquals("creds-1", connection.getCredentialsId());
    }

    @Test
    void testValidateAcceptsValidInput() {
        RemoteConnection connection = new RemoteConnection("server1", "http://jenkins1.example.com", "creds-1");

        connection.validate();
    }

    @Test
    void testValidateRejectsEmptyServerId() {
        RemoteConnection connection = new RemoteConnection("", "http://jenkins1.example.com", "creds-1");

        assertThrows(IllegalArgumentException.class, connection::validate);
    }

    @Test
    void testValidateRejectsNullServerId() {
        RemoteConnection connection = new RemoteConnection(null, "http://jenkins1.example.com", "creds-1");

        assertThrows(IllegalArgumentException.class, connection::validate);
    }

    @Test
    void testValidateRejectsEmptyUrl() {
        RemoteConnection connection = new RemoteConnection("server1", "", "creds-1");

        assertThrows(IllegalArgumentException.class, connection::validate);
    }

    @Test
    void testValidateRejectsNullUrl() {
        RemoteConnection connection = new RemoteConnection("server1", null, "creds-1");

        assertThrows(IllegalArgumentException.class, connection::validate);
    }

    @Test
    void testValidateAllowsNullCredentialsId() {
        RemoteConnection connection = new RemoteConnection("server1", "http://jenkins1.example.com", null);

        connection.validate();
    }

    @Test
    void testValidateAcceptsHttpsUrl() {
        RemoteConnection connection =
                new RemoteConnection("server1", "https://jenkins1.example.com/jenkins", "creds-1");

        connection.validate();
    }

    @Test
    void testValidateRejectsNonHttpUrl() {
        // M1F L-b: the base URL is used by the HTTP transport; non-http(s) schemes are rejected up front.
        assertThrows(
                IllegalArgumentException.class,
                () -> new RemoteConnection("server1", "file:///etc/passwd", "creds-1").validate());
        assertThrows(
                IllegalArgumentException.class,
                () -> new RemoteConnection("server1", "ftp://jenkins1.example.com", "creds-1").validate());
        assertThrows(
                IllegalArgumentException.class,
                () -> new RemoteConnection("server1", "jenkins1.example.com", "creds-1").validate());
    }

    @Test
    void testDoCheckUrl() {
        RemoteConnection.DescriptorImpl descriptor = new RemoteConnection.DescriptorImpl();

        assertEquals(FormValidation.Kind.OK, descriptor.doCheckUrl("https://jenkins1.example.com").kind);
        assertEquals(FormValidation.Kind.OK, descriptor.doCheckUrl("http://jenkins1.example.com").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckUrl("file:///etc/passwd").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckUrl("").kind);
        assertEquals(FormValidation.Kind.ERROR, descriptor.doCheckUrl(null).kind);
    }

    @Test
    void testEqualsAndHashCode() {
        RemoteConnection a = new RemoteConnection("server1", "http://jenkins1.example.com", "creds-1");
        RemoteConnection b = new RemoteConnection("server1", "http://jenkins1.example.com", "creds-1");
        RemoteConnection c = new RemoteConnection("server2", "http://jenkins2.example.com", "creds-2");

        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertNotEquals(a, c);
    }
}
