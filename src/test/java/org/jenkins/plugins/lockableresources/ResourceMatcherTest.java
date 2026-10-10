/*
 * The MIT License
 *
 * See the "LICENSE.txt" file for full copyright and license information.
 */
package org.jenkins.plugins.lockableresources;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ResourceMatcherTest {
    @Test
    void reassignsBroadRequestForLaterSingleton() {
        assertEquals(List.of("b", "a"), ResourceMatcher.match(List.of(List.of("a", "b"), List.of("a"))));
    }

    @Test
    void followsMultipleReassignmentsWithEquallySpecificRequests() {
        assertEquals(
                List.of("a", "c", "d", "b"),
                ResourceMatcher.match(
                        List.of(List.of("a", "b"), List.of("b", "c"), List.of("c", "d"), List.of("a", "b"))));
    }

    @Test
    void rejectsInsufficientDistinctCandidatesEvenWhenEverySlotHasEnoughCandidates() {
        assertNull(ResourceMatcher.match(List.of(List.of("a", "b"), List.of("a", "b"), List.of("a", "b"))));
        assertNull(ResourceMatcher.match(List.of(List.of("a"), List.of("a"))));
        assertNull(ResourceMatcher.match(List.of(Collections.<String>emptyList())));
    }

    @Test
    void supportsEmptyRequest() {
        assertEquals(List.of(), ResourceMatcher.match(List.of()));
    }

    @Test
    void handlesLongAugmentingPathsWithoutRecursion() {
        int length = 2000;
        List<List<Integer>> slots = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            slots.add(List.of(i, i + 1));
        }
        slots.add(List.of(0));
        List<Integer> result = ResourceMatcher.match(slots);
        assertNotNull(result);
        for (int i = 0; i < length; i++) {
            assertEquals(i + 1, result.get(i));
        }
        assertEquals(0, result.get(length));
    }

    @Test
    void agreesWithExhaustiveSearchForAllThreeSlotFourResourceGraphs() {
        for (int graph = 0; graph < (1 << 12); graph++) {
            List<List<Integer>> slots = new ArrayList<>();
            for (int slot = 0; slot < 3; slot++) {
                List<Integer> candidates = new ArrayList<>();
                for (int resource = 0; resource < 4; resource++) {
                    if ((graph & (1 << (slot * 4 + resource))) != 0) {
                        candidates.add(resource);
                    }
                }
                slots.add(candidates);
            }
            List<Integer> result = ResourceMatcher.match(slots);
            assertEquals(hasCompleteAssignment(slots, 0, new HashSet<>()), result != null, "Graph " + graph);
            if (result != null) {
                assertEquals(slots.size(), result.size());
                assertEquals(result.size(), new HashSet<>(result).size());
                for (int slot = 0; slot < slots.size(); slot++) {
                    assertTrue(slots.get(slot).contains(result.get(slot)));
                }
            }
        }
    }

    private boolean hasCompleteAssignment(List<List<Integer>> slots, int slot, Set<Integer> used) {
        if (slot == slots.size()) {
            return true;
        }
        for (int resource : slots.get(slot)) {
            if (used.add(resource)) {
                if (hasCompleteAssignment(slots, slot + 1, used)) {
                    return true;
                }
                used.remove(resource);
            }
        }
        return false;
    }
}
