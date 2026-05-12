/*
 * The MIT License
 *
 * Copyright (c) 2019, CloudBees, Inc.
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hudson.Util;
import hudson.security.ACL;
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy;
import org.jenkins.plugins.lockableresources.actions.LockableResourcesRootAction;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class LockableResourceRootActionSEC1361Test {

    @Test
    void regularCase(JenkinsRule j) throws Exception {
        checkXssWithResourceName(j, "resource1");
    }

    @Test
    @Issue("SECURITY-1361")
    void noXssOnClick(JenkinsRule j) throws Exception {
        checkXssWithResourceName(j, "\"); alert(123);//");
    }

    private static void checkXssWithResourceName(JenkinsRule j, String resourceName) throws Exception {
        LockableResourcesManager.get().createResource(resourceName);

        // The resources table loads rows asynchronously (DataTables TableModel), so validate XSS safety
        // via the JSON row HTML: ensure the resource name is escaped inside an HTML attribute.
        j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
        j.jenkins.setAuthorizationStrategy(new FullControlOnceLoggedInAuthorizationStrategy());
        org.springframework.security.core.context.SecurityContextHolder.getContext()
                .setAuthentication(ACL.SYSTEM2);

        LockableResourcesRootAction action = new LockableResourcesRootAction();
        String json = action.getTableRows("lockable-resources");
        JsonNode rows = new ObjectMapper().readTree(json);

        String escapedName = Util.escape(resourceName);
        String expectedAttr = "data-resource-name=\"" + escapedName + "\"";
        String unsafeAttr = "data-resource-name=\"" + resourceName + "\"";
        boolean foundEscapedCheckbox = false;
        for (JsonNode row : rows) {
            JsonNode selectCell = row.get("select");
            if (selectCell == null || !selectCell.isTextual()) {
                continue;
            }
            String selectHtml = selectCell.asText();
            if (selectHtml.contains(expectedAttr)) {
                foundEscapedCheckbox = true;
                assertTrue(selectHtml.contains("lockable-resources-select"), "Expected selection checkbox");
                if (!resourceName.equals(escapedName)) {
                    assertTrue(!selectHtml.contains(unsafeAttr), "Expected unsafe attribute value to be escaped");
                }
            }
        }

        assertTrue(foundEscapedCheckbox, "Expected a selection checkbox for the created resource");
    }
}
