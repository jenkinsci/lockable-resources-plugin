/* SPDX-License-Identifier: MIT
 * Copyright (c) 2020, Tobias Gruetzmacher
 */
package org.jenkins.plugins.lockableresources;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

import com.gargoylesoftware.htmlunit.html.HtmlElement;
import com.gargoylesoftware.htmlunit.html.HtmlElementUtil;
import com.gargoylesoftware.htmlunit.html.HtmlPage;
import hudson.model.FreeStyleProject;
import hudson.model.Queue;
import java.io.IOException;
import java.util.List;
import jenkins.model.Jenkins;
import net.sf.json.JSONArray;
import net.sf.json.JSONObject;
import org.jvnet.hudson.test.JenkinsRule;

public final class TestHelpers {

  private static final int SLEEP_TIME = 100;
  private static final int MAX_WAIT = 5000;

  // Utility class
  private TestHelpers() {}

  public static void waitForQueue(Jenkins jenkins, FreeStyleProject job)
      throws InterruptedException {
    waitForQueue(jenkins, job, Queue.Item.class);
  }

  /** Schedule a build and make sure it has been added to Jenkins' queue. */
  public static void waitForQueue(Jenkins jenkins, FreeStyleProject job, Class<?> itemType)
      throws InterruptedException {
    System.out.print("Waiting for job to be queued...");
    int waitTime = 0;
    while (!itemType.isInstance(jenkins.getQueue().getItem(job)) && waitTime < MAX_WAIT) {
      Thread.sleep(SLEEP_TIME);
      waitTime += SLEEP_TIME;
      if (waitTime % 1000 == 0) {
        System.out.print(" " + waitTime / 1000 + "s");
      }
    }
    System.out.println();
  }

  /**
   * Get a resource from the JSON API and validate some basic properties. This allows to verify that
   * the API returns sane values while running other tests.
   */
  public static JSONObject getResourceFromApi(
      JenkinsRule rule, String resourceName, boolean isLocked) throws IOException {
    JSONObject data = getApiData(rule);
    JSONArray resources = data.getJSONArray("resources");
    assertThat(resources, is(not(nullValue())));
    JSONObject res =
        (JSONObject)
            (resources.stream()
                .filter(e -> resourceName.equals(((JSONObject) e).getString("name")))
                .findAny()
                .orElseThrow(
                    () -> new AssertionError("Could not find '" + resourceName + "' in API.")));
    assertThat(res, hasEntry("locked", isLocked));
    return res;
  }

  public static JSONObject getApiData(JenkinsRule rule) throws IOException {
    return rule.getJSON("plugin/lockable-resources/api/json").getJSONObject();
  }

  // Currently assumes one resource or only clicks the button for the first resource
  public static void clickButton(JenkinsRule.WebClient wc, String action) throws Exception {
    HtmlPage htmlPage = wc.goTo("lockable-resources");
    List<HtmlElement> allButtons = htmlPage.getDocumentElement().getElementsByTagName("button");

    HtmlElement reserveButton = null;
    for (HtmlElement b : allButtons) {
      String onClick = b.getAttribute("onClick");
      if (onClick != null && onClick.contains(action)) {
        reserveButton = b;
      }
    }
    HtmlElementUtil.click(reserveButton);
  }
}
