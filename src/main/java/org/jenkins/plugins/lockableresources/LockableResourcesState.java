/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources;

import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.Extension;
import hudson.ExtensionList;
import hudson.XmlFile;
import hudson.init.InitMilestone;
import hudson.init.Initializer;
import hudson.model.Saveable;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import jenkins.model.GlobalConfiguration;
import jenkins.model.Jenkins;
import org.jenkins.plugins.lockableresources.queue.QueuedContextStruct;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/**
 * Runtime state of the plugin, kept apart from the global configuration in {@link LockableResourcesManager}.
 * Holds the queue of {@link LockStep} executions waiting for resources.
 *
 * <p>Stored in its own file in {@code JENKINS_HOME}. Saves share the coalesced async save of
 * {@link LockableResourcesManager} (see {@link LockableResourcesManager#saveState}). Saving does not fire
 * {@link hudson.model.listeners.SaveableListener}: these are not configuration changes.
 *
 * <p>All access must hold {@link LockableResourcesManager#syncResources}.
 */
@Extension
@Restricted(NoExternalUse.class)
public final class LockableResourcesState implements Saveable {

    private static final Logger LOGGER = Logger.getLogger(LockableResourcesState.class.getName());

    /**
     * Only used when a resource is locked by {@link LockStep}, otherwise (freestyle builds) the regular
     * Jenkins queue is used.
     */
    private final List<QueuedContextStruct> queuedContexts = new ArrayList<>();

    public LockableResourcesState() {
        load();
    }

    @NonNull
    public static LockableResourcesState get() {
        return ExtensionList.lookupSingleton(LockableResourcesState.class);
    }

    /** Loads the state before jobs are loaded, so legacy queue entries are migrated before Pipelines resume. */
    @Initializer(after = InitMilestone.EXTENSIONS_AUGMENTED, before = InitMilestone.JOB_LOADED)
    public static void loadOnStartup() {
        get();
    }

    static XmlFile getStateFile() {
        return new XmlFile(
                Jenkins.XSTREAM2,
                new File(Jenkins.get().getRootDir(), LockableResourcesState.class.getName() + ".xml"));
    }

    /** Read-only view of the queue. */
    @NonNull
    public List<QueuedContextStruct> getQueuedContexts() {
        return Collections.unmodifiableList(queuedContexts);
    }

    /** The live, mutable queue. Callers must hold {@link LockableResourcesManager#syncResources}. */
    @NonNull
    List<QueuedContextStruct> queuedContexts() {
        return queuedContexts;
    }

    @Override
    public void save() {
        LockableResourcesManager lrm = GlobalConfiguration.all().get(LockableResourcesManager.class);
        if (lrm != null) {
            lrm.saveState(this);
        } else {
            synchronized (LockableResourcesManager.syncResources) {
                write();
            }
        }
    }

    /** Writes the state file now. Callers must hold {@link LockableResourcesManager#syncResources}. */
    void write() {
        XmlFile file = getStateFile();
        try {
            file.write(this);
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, "Failed to save " + file, e);
        }
    }

    private void load() {
        XmlFile file = getStateFile();
        boolean existed = file.exists();
        if (existed) {
            try {
                file.unmarshal(this);
            } catch (IOException e) {
                LOGGER.log(Level.WARNING, "Failed to load " + file, e);
            }
        }
        migrateLegacyQueue(existed);
    }

    /**
     * Moves queue entries from an old {@code LockableResourcesManager.xml}. The state file is saved before the
     * manager file is rewritten without them, so a crash in between can only leave the entries in both files.
     * If the state file already existed it wins, and the legacy entries are dropped.
     */
    private void migrateLegacyQueue(boolean stateFileExisted) {
        LockableResourcesManager lrm = GlobalConfiguration.all().get(LockableResourcesManager.class);
        if (lrm == null) {
            return;
        }
        synchronized (LockableResourcesManager.syncResources) {
            List<QueuedContextStruct> legacy = lrm.drainLegacyQueuedContexts();
            if (legacy == null || legacy.isEmpty()) {
                return;
            }
            if (stateFileExisted) {
                LOGGER.log(
                        Level.WARNING,
                        "Dropping {0} queued lock request(s) found in LockableResourcesManager.xml: {1} already exists"
                                + " and wins",
                        new Object[] {legacy.size(), getStateFile()});
            } else {
                queuedContexts.addAll(legacy);
                if (lrm.isSaveEnabled()) {
                    write();
                }
                LOGGER.log(
                        Level.INFO,
                        "Migrated {0} queued lock request(s) from LockableResourcesManager.xml to {1}",
                        new Object[] {legacy.size(), getStateFile()});
            }
            lrm.save();
        }
    }
}
