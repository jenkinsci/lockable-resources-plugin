/*
 * The MIT License
 *
 * Copyright 2014 Aki Asikainen.
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

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.jvnet.hudson.test.JenkinsRule;

public class LockableResourcesManagerTest {

  @Rule public JenkinsRule j = new JenkinsRule();

  static final int LOOP_AMOUNT = 1000;
  static final int THREAD_AMOUNT = 2;

  static LockableResourcesManager manager;
  static ExecutorService executorService;

  public LockableResourcesManagerTest() {}

  @BeforeClass
  public static void setUpClass() {
    executorService = Executors.newFixedThreadPool(4);
  }

  @AfterClass
  public static void tearDownClass() {}

  @Before
  public void setUp() {
    // BeforeClass is executed before the JenkinsRule, therefore set the manager here
    manager = LockableResourcesManager.get();
  }

  @After
  public void tearDown() {}

  /** Test to simulate a concurrent write (create) and read (lock) on the resources list. */
  @Test
  public void testConcurrentCreateAndFetch() {
    // start multiple thread jobs that create and fetch in parallel
    List<Future<?>> futures = new ArrayList<>();
    for (int i = 0; i < LOOP_AMOUNT; i++) {
      final int k = i;
      futures.add(executorService.submit(() -> manager.fromName("r" + k)));
      futures.add(executorService.submit(() -> createLockAndFree("r" + k, "l" + k, true)));
    }
    for (int i = 0; i < LOOP_AMOUNT; i++) {
      manager.fromName("r" + i);
    }

    // wait until they are finished
    waitUntilFuturesAreReady(futures);

    // since all locks are ephemeral and unlocked, the amount of free resources must be 0
    assertEquals(0, manager.getFreeResourceAmount("l"));
  }

  private void sleep(long ms) {
    try {
      Thread.sleep(ms);
    } catch (Exception e) {
    }
  }

  private void createLockAndFree(String name, String label, boolean ephemeral) {
    // create new resource
    manager.createResourceWithLabel(name, label + " l");

    // set the created resource to ephemeral
    LockableResource resource = manager.fromName(name);
    sleep(20);
    resource.setEphemeral(ephemeral);
    assertEquals(name, resource.getName());

    // lock the created resource
    List<LockableResource> resources = Arrays.asList(resource);
    manager.lock(new HashSet<>(resources), null, null);

    // unlock and remove the created resource
    List<String> resourceStrings = Arrays.asList(name);
    manager.unlockNames(resourceStrings, null, false);
  }

  private void waitUntilFuturesAreReady(List<Future<?>> futures) {
    for (Future<?> future : futures) {
      try {
        future.get();
      } catch (Exception e) {
      }
    }
  }
}
