package org.jenkins.plugins.lockableresources.queue;

import java.util.List;

import org.jenkins.plugins.lockableresources.LockableResource;

public class LockableResourcesCandidatesStruct {

	public List<LockableResource> candidates;
	public int requiredAmount;
	public List<LockableResource> selected;

	public LockableResourcesCandidatesStruct(List<LockableResource> candidates, int requiredAmount) {
		this.candidates = candidates;
		this.requiredAmount = requiredAmount;
	}
}
