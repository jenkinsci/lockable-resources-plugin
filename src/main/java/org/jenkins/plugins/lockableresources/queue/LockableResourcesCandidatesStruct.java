package org.jenkins.plugins.lockableresources.queue;

import java.util.List;

import org.jenkins.plugins.lockableresources.LockableResource;

public class LockableResourcesCandidatesStruct {

	private List<LockableResource> candidates;
	private int requiredAmount;
	private List<LockableResource> selected;

	public LockableResourcesCandidatesStruct(List<LockableResource> candidates, int requiredAmount) {
		this.candidates = candidates;
		this.requiredAmount = requiredAmount;
	}

    @Override
    public String toString()
    {
        return String.format("LockableResourcesCandidatesStruct [candidates=%s, requiredAmount=%s, selected=%s]",
          candidates, requiredAmount, selected);
    }

    public List<LockableResource> getCandidates() {
      return candidates;
    }

    public List<LockableResource> getSelected() {
      return selected;
    }

    public int getRequiredAmount() {
      return requiredAmount;
    }

    public void setSelected(List<LockableResource> selected) {
      this.selected = selected;
    }

    public void setCandidates(List<LockableResource> candidates) {
      this.candidates = candidates;
    }

    public void setRequiredAmount(int requiredAmount) {
      this.requiredAmount = requiredAmount;
    }

}
