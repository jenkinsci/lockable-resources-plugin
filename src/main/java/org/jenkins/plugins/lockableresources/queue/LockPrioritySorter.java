/* * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * *
 * Copyright (c) 2013, 6WIND S.A. All rights reserved.                 *
 *                                                                     *
 * This file is part of the Jenkins Lockable Resources Plugin and is   *
 * published under the MIT license.                                    *
 *                                                                     *
 * See the "LICENSE.txt" file for more information.                    *
 * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * * */
package org.jenkins.plugins.lockableresources.queue;

import hudson.Extension;
import hudson.model.Action;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Queue.BuildableItem;
import hudson.model.StringParameterValue;
import hudson.model.queue.QueueSorter;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import org.jenkins.plugins.lockableresources.LockableResourcesManager;

@Extension
public class LockPrioritySorter extends QueueSorter {

	static final PriorityComparator COMPARATOR = new PriorityComparator();

	@Override
	public void sortBuildableItems(List<BuildableItem> buildables) {
		Collections.sort(buildables, COMPARATOR);
	}

	static class PriorityComparator implements Comparator<BuildableItem> {

		public int compare(BuildableItem o1, BuildableItem o2) {
			return getPriority(o1) - getPriority(o2);
		}

		public int getPriority(BuildableItem item) {
			String paramName = LockableResourcesManager.get()
					.getPriorityParameterName();
			int defaultPriority = LockableResourcesManager.get()
					.getDefaultPriority();
			for (Action action : item.getActions()) {
				if (action instanceof ParametersAction) {
					ParametersAction pa = (ParametersAction) action;
					for (ParameterValue p : pa.getParameters()) {
						if (p instanceof StringParameterValue) {
							if (p.getName().equals(paramName)) {
								String value = ((StringParameterValue) p).value;
								try {
									return Integer.parseInt(value);
								} catch (NumberFormatException e) {
									return defaultPriority;
								}
							}
						}
					}
				}
			}
			return defaultPriority;
		}
	}

}
