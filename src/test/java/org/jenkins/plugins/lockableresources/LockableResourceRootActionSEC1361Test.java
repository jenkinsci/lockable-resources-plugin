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

import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;

import com.gargoylesoftware.htmlunit.FailingHttpStatusCodeException;
import com.gargoylesoftware.htmlunit.html.HtmlElement;
import com.gargoylesoftware.htmlunit.html.HtmlElementUtil;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import hudson.security.FullControlOnceLoggedInAuthorizationStrategy;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;

public class LockableResourceRootActionSEC1361Test {

  @Rule public JenkinsRule j = new JenkinsRule();

  @Test
  public void regularCase() throws Exception {
    checkXssWithResourceName("resource1");
  }

  @Test
  @Issue("SECURITY-1361")
  public void noXssOnClick() throws Exception {
    checkXssWithResourceName("\"); alert(123);//");
  }

  private void checkXssWithResourceName(String resourceName) throws Exception {
    LockableResourcesManager.get().createResource(resourceName);

    j.jenkins.setSecurityRealm(j.createDummySecurityRealm());
    j.jenkins.setAuthorizationStrategy(new FullControlOnceLoggedInAuthorizationStrategy());

    JenkinsRule.WebClient wc = j.createWebClient();
    wc.login("user");

    final AtomicReference<String> lastAlertReceived = new AtomicReference<>();
    wc.setAlertHandler((page, s) -> lastAlertReceived.set(s));

    HtmlPage htmlPage = wc.goTo("lockable-resources");
    assertThat(lastAlertReceived.get(), nullValue());

    // currently only one button but perhaps in future version of the core/plugin,
    // other buttons will be added to the layout
    List<HtmlElement> allButtons = htmlPage.getDocumentElement().getElementsByTagName("button");
    assertThat(allButtons.size(), greaterThanOrEqualTo(1));

    HtmlElement reserveButton = null;
    for (HtmlElement b : allButtons) {
      String onClick = b.getAttribute("onClick");
      if (onClick != null && onClick.contains("reserve")) {
        reserveButton = b;
      }
    }
    assertThat(reserveButton, not(nullValue()));

    try {
      HtmlElementUtil.click(reserveButton);
    } catch (FailingHttpStatusCodeException e) {
      // only happen if we have a XSS, but it's managed using the AlertHandler to ensure it's a XSS
      // and not just an invalid page
    }
    assertThat(lastAlertReceived.get(), nullValue());
  }
}
