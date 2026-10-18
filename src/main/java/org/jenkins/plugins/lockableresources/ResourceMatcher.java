/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources;

import edu.umd.cs.findbugs.annotations.CheckForNull;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Finds a complete, distinct assignment using augmenting paths, in O(slots * edges) time. */
final class ResourceMatcher {
    private ResourceMatcher() {}

    /**
     * Returns one resource per slot in input order, or null when no complete matching exists. Each slot tries its
     * candidates in list order, so earlier candidates are preferred.
     */
    @CheckForNull
    static <T> List<T> match(List<List<T>> candidatesPerSlot) {
        List<T> assignments = new ArrayList<>(Collections.nCopies(candidatesPerSlot.size(), null));
        Map<T, Integer> owners = new HashMap<>();
        for (int slot = 0; slot < candidatesPerSlot.size(); slot++) {
            if (!augment(slot, candidatesPerSlot, assignments, owners)) {
                return null;
            }
        }
        return assignments;
    }

    private static <T> boolean augment(
            int start, List<List<T>> candidatesPerSlot, List<T> assignments, Map<T, Integer> owners) {
        int[] parents = new int[candidatesPerSlot.size()];
        Arrays.fill(parents, -2);
        parents[start] = -1;
        Map<Integer, T> incomingResources = new HashMap<>();
        ArrayDeque<Integer> pending = new ArrayDeque<>();
        pending.add(start);

        // Breadth-first search avoids recursion depth limits on long reassignment chains.
        while (!pending.isEmpty()) {
            int slot = pending.remove();
            for (T resource : candidatesPerSlot.get(slot)) {
                Integer owner = owners.get(resource);
                if (owner == null) {
                    int current = slot;
                    T replacement = resource;
                    while (current != -1) {
                        owners.put(replacement, current);
                        assignments.set(current, replacement);
                        replacement = incomingResources.get(current);
                        current = parents[current];
                    }
                    return true;
                }
                if (parents[owner] == -2) {
                    parents[owner] = slot;
                    incomingResources.put(owner, resource);
                    pending.add(owner);
                }
            }
        }
        return false;
    }
}
