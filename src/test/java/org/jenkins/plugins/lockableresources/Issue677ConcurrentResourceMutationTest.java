package org.jenkins.plugins.lockableresources;

import hudson.model.Run;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;
import org.jenkins.plugins.lockableresources.util.Constants;
import org.jenkinsci.plugins.workflow.steps.StepContext;
import org.junit.jupiter.api.Test;
import org.jvnet.hudson.test.Issue;
import org.jvnet.hudson.test.JenkinsRule;
import org.jvnet.hudson.test.junit.jupiter.WithJenkins;

@WithJenkins
class Issue677ConcurrentResourceMutationTest {

    @Test
    @Issue("https://github.com/jenkinsci/lockable-resources-plugin/issues/677")
    void cleanupDoesNotOverlapResourceAddition(JenkinsRule j) throws Throwable {
        exerciseConcurrentMutation(j);
    }

    private static void unlockR1(LockableResourcesManager manager, Run<?, ?> owner) {
        manager.unlockNames(Collections.singletonList("R1"), owner);
    }

    private static final long TIMEOUT_SECONDS = 15;

    private static void exerciseConcurrentMutation(JenkinsRule j) throws Throwable {
        String previousDisableSave = System.getProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE);
        try {
            System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, "true");
            runConcurrentMutation(j);
        } finally {
            if (previousDisableSave == null) {
                System.clearProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE);
            } else {
                System.setProperty(Constants.SYSTEM_PROPERTY_DISABLE_SAVE, previousDisableSave);
            }
        }
    }

    private static void runConcurrentMutation(JenkinsRule j) throws Throwable {
        LockableResourcesManager manager = LockableResourcesManager.get();
        Field resourcesField = LockableResourcesManager.class.getDeclaredField("resources");
        resourcesField.setAccessible(true);
        List<LockableResource> original = manager.getResources();
        PausingResourceList observed = null;
        Thread creator = null;
        Thread cleaner = null;
        AtomicReference<Throwable> additionFailure = new AtomicReference<>();
        AtomicReference<Throwable> cleanupFailure = new AtomicReference<>();
        Throwable failure = null;
        try {
            Run<?, ?> owner = j.buildAndAssertSuccess(j.createFreeStyleProject("resource-owner"));
            require(manager.createResource("R1"), "Could not create temporary R1");
            require(manager.createResource("R2"), "Could not create temporary R2");
            require(
                    manager.lock(Collections.singletonList(manager.fromName("R1")), owner, (StepContext) null),
                    "Could not lock R1");

            observed = new PausingResourceList(original);
            synchronized (LockableResourcesManager.syncResources) {
                resourcesField.set(manager, observed);
            }
            PausingResourceList list = observed;
            creator = new Thread(
                    () -> {
                        try {
                            require(manager.addResource(new LockableResource("R3")), "R3 was not added");
                        } catch (Throwable problem) {
                            additionFailure.set(problem);
                        }
                    },
                    "issue677-creator");
            cleaner = new Thread(
                    () -> {
                        try {
                            unlockR1(manager, owner);
                        } catch (Throwable problem) {
                            cleanupFailure.set(problem);
                        }
                    },
                    "issue677-cleaner");
            creator.setDaemon(true);
            cleaner.setDaemon(true);
            list.creator = creator;
            list.cleaner = cleaner;

            creator.start();
            await(list.additionEntered, "Creator did not reach the resource-list addition");
            cleaner.start();

            // The old monitor permits cleanup to iterate while addResource is still active.
            // The current monitor blocks cleanup until the addition has completed.
            String schedule = awaitCleanupSchedulingPoint(list, creator, cleaner);
            list.allowAddition.countDown();
            join(creator);
            join(cleaner);

            List<String> names = new ArrayList<>();
            boolean hasNull = false;
            for (LockableResource resource : list) {
                hasNull |= resource == null;
                names.add(resource == null ? "<null>" : resource.getName());
            }
            System.out.println("Issue #677 diagnostic: schedule=" + schedule
                    + ", additionFailure=" + additionFailure.get()
                    + ", cleanupFailure=" + cleanupFailure.get()
                    + ", hasNull=" + hasNull
                    + ", resources=" + names);

            // Worker failures are collected after shutdown, including failures during cleanup.
            if (additionFailure.get() == null && cleanupFailure.get() == null) {
                require(!hasNull, "Resource list contains a null entry");
                require(manager.fromName("R1") == null, "R1 was not removed");
                require(manager.fromName("R2") != null, "R2 disappeared");
                require(manager.fromName("R3") != null, "R3 disappeared");
            }
        } catch (Throwable problem) {
            failure = problem;
            if (problem instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
        } finally {
            if (observed != null) {
                observed.releaseAll();
            }
            failure = stopWorkers(failure, creator, cleaner);
            if (additionFailure.get() != null) {
                failure = addFailure(failure, new AssertionError("Resource creation failed", additionFailure.get()));
            }
            if (cleanupFailure.get() != null) {
                failure = addFailure(
                        failure,
                        new AssertionError("Resource cleanup overlapped creation and failed", cleanupFailure.get()));
            }
            // Intrinsic-monitor acquisition is not interruptible. A surviving worker may
            // retain syncResources, so fail without waiting for that monitor in this case.
            if ((creator == null || !creator.isAlive()) && (cleaner == null || !cleaner.isAlive())) {
                try {
                    synchronized (LockableResourcesManager.syncResources) {
                        resourcesField.set(manager, original);
                    }
                } catch (Throwable problem) {
                    failure = addFailure(failure, problem);
                }
            } else {
                failure = addFailure(failure, new AssertionError("Resources not restored: a worker is still alive"));
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private static Throwable stopWorkers(Throwable failure, Thread... workers) {
        boolean interrupted = Thread.interrupted();
        try {
            for (Thread worker : workers) {
                if (worker != null && worker.isAlive()) {
                    worker.interrupt();
                }
            }
            for (Thread worker : workers) {
                if (worker == null) {
                    continue;
                }
                try {
                    join(worker);
                } catch (InterruptedException problem) {
                    interrupted = true;
                    failure = addFailure(failure, problem);
                } catch (Throwable problem) {
                    failure = addFailure(failure, problem);
                }
            }
            return failure;
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static Throwable addFailure(Throwable failure, Throwable additional) {
        if (failure == null) {
            return additional;
        }
        if (failure != additional) {
            failure.addSuppressed(additional);
        }
        return failure;
    }

    private static String awaitCleanupSchedulingPoint(PausingResourceList list, Thread creator, Thread cleaner) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT_SECONDS);
        while (System.nanoTime() < deadline) {
            if (list.cleanupIterating.getCount() == 0) {
                return "cleanup iterating during addition";
            }
            ThreadInfo info = ManagementFactory.getThreadMXBean().getThreadInfo(cleaner.getId());
            if (info != null
                    && info.getThreadState() == Thread.State.BLOCKED
                    && info.getLockOwnerId() == creator.getId()) {
                return "cleanup blocked by creator monitor";
            }
            require(cleaner.isAlive(), "Cleanup exited before a scheduling point was observed");
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        throw new AssertionError("Neither overlapping iteration nor monitor exclusion was observed");
    }

    private static void await(CountDownLatch latch, String message) {
        try {
            require(latch.await(TIMEOUT_SECONDS, TimeUnit.SECONDS), message);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(message, failure);
        }
    }

    private static void join(Thread thread) throws InterruptedException {
        thread.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
        require(!thread.isAlive(), "Worker did not terminate: " + thread.getName());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    /**
     * Keeps ArrayList's mutations and fail-fast iterator intact. The only added behavior is
     * scheduling: pause the creator after its existence check, then let it finish while the
     * historical cleanup iterator is active. No null entries or exceptions are injected.
     */
    private static final class PausingResourceList extends ArrayList<LockableResource> {
        private final CountDownLatch additionEntered = new CountDownLatch(1);
        private final CountDownLatch allowAddition = new CountDownLatch(1);
        private final CountDownLatch additionFinished = new CountDownLatch(1);
        private final CountDownLatch cleanupIterating = new CountDownLatch(1);
        private Thread creator;
        private Thread cleaner;

        PausingResourceList(List<LockableResource> resources) {
            super(resources);
        }

        void releaseAll() {
            additionEntered.countDown();
            allowAddition.countDown();
            additionFinished.countDown();
            cleanupIterating.countDown();
        }

        @Override
        public boolean add(LockableResource resource) {
            if (Thread.currentThread() != creator) {
                return super.add(resource);
            }
            try {
                additionEntered.countDown();
                await(allowAddition, "Addition was not released");
                return super.add(resource);
            } finally {
                additionFinished.countDown();
            }
        }

        @Override
        public Iterator<LockableResource> iterator() {
            Iterator<LockableResource> delegate = super.iterator();
            return new Iterator<LockableResource>() {
                @Override
                public boolean hasNext() {
                    return delegate.hasNext();
                }

                @Override
                public LockableResource next() {
                    LockableResource resource = delegate.next();
                    if (Thread.currentThread() == cleaner) {
                        cleanupIterating.countDown();
                        await(additionFinished, "Addition did not finish while cleanup was iterating");
                    }
                    return resource;
                }

                @Override
                public void remove() {
                    delegate.remove();
                }
            };
        }
    }
}
