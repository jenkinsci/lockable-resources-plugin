/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources.actions;

import hudson.model.InvisibleAction;
import org.kohsuke.accmod.Restricted;
import org.kohsuke.accmod.restrictions.NoExternalUse;

/** Marks a resource group admission failure so the job property can veto Freestyle builders. */
@Restricted(NoExternalUse.class)
public final class GroupLockFailureAction extends InvisibleAction {}
