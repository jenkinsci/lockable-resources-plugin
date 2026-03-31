package org.jenkins.plugins.lockableresources.queue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import hudson.EnvVars;
import hudson.model.ParameterValue;
import hudson.model.ParametersAction;
import hudson.model.Queue;
import hudson.model.StringParameterValue;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

class UtilsTest {

    // -----------------------------------------------------------------------
    // containsVariable()
    // -----------------------------------------------------------------------

    @Test
    void containsVariable() {
        assertTrue(Utils.containsVariable("${MY_PARAM}"));
        assertTrue(Utils.containsVariable("prefix-${MY_PARAM}"));
        assertTrue(Utils.containsVariable("${A}-${B}"));
        assertTrue(Utils.containsVariable("resource-${BRANCH_NAME}-lock"));

        assertFalse(Utils.containsVariable(null));
        assertFalse(Utils.containsVariable(""));
        assertFalse(Utils.containsVariable("plain-resource"));
        assertFalse(Utils.containsVariable("$NOT_A_VAR"));
        assertFalse(Utils.containsVariable("${}")); // empty var name
    }

    // -----------------------------------------------------------------------
    // getParametersAsEnvVars()
    // -----------------------------------------------------------------------

    @Test
    void getParametersAsEnvVars_noActions() {
        Queue.Item item = mock(Queue.Item.class);
        when(item.getActions(ParametersAction.class)).thenReturn(Collections.emptyList());

        EnvVars env = Utils.getParametersAsEnvVars(item);

        assertNotNull(env);
        assertTrue(env.isEmpty());
    }

    @Test
    void getParametersAsEnvVars_singleParam() {
        StringParameterValue param = new StringParameterValue("RESOURCE", "my-resource");
        ParametersAction action = new ParametersAction(param);

        Queue.Item item = mock(Queue.Item.class);
        when(item.getActions(ParametersAction.class)).thenReturn(List.of(action));

        EnvVars env = Utils.getParametersAsEnvVars(item);

        assertEquals(1, env.size());
        assertEquals("my-resource", env.get("RESOURCE"));
    }

    @Test
    void getParametersAsEnvVars_multipleParams() {
        StringParameterValue p1 = new StringParameterValue("RESOURCE_NAME", "res-1");
        StringParameterValue p2 = new StringParameterValue("LABEL", "team-alpha");
        StringParameterValue p3 = new StringParameterValue("COUNT", "3");
        ParametersAction action = new ParametersAction(p1, p2, p3);

        Queue.Item item = mock(Queue.Item.class);
        when(item.getActions(ParametersAction.class)).thenReturn(List.of(action));

        EnvVars env = Utils.getParametersAsEnvVars(item);

        assertEquals(3, env.size());
        assertEquals("res-1", env.get("RESOURCE_NAME"));
        assertEquals("team-alpha", env.get("LABEL"));
        assertEquals("3", env.get("COUNT"));
    }

    @Test
    void getParametersAsEnvVars_multipleActions() {
        StringParameterValue p1 = new StringParameterValue("A", "val-a");
        ParametersAction action1 = new ParametersAction(p1);
        StringParameterValue p2 = new StringParameterValue("B", "val-b");
        ParametersAction action2 = new ParametersAction(p2);

        Queue.Item item = mock(Queue.Item.class);
        when(item.getActions(ParametersAction.class)).thenReturn(List.of(action1, action2));

        EnvVars env = Utils.getParametersAsEnvVars(item);

        assertEquals(2, env.size());
        assertEquals("val-a", env.get("A"));
        assertEquals("val-b", env.get("B"));
    }

    @Test
    void getParametersAsEnvVars_nullValueSkipped() {
        ParameterValue nullValParam = mock(ParameterValue.class);
        when(nullValParam.getName()).thenReturn("NULL_PARAM");
        when(nullValParam.getValue()).thenReturn(null);

        StringParameterValue goodParam = new StringParameterValue("GOOD", "ok");
        ParametersAction action = mock(ParametersAction.class);
        when(action.getParameters()).thenReturn(List.of(nullValParam, goodParam));

        Queue.Item item = mock(Queue.Item.class);
        when(item.getActions(ParametersAction.class)).thenReturn(List.of(action));

        EnvVars env = Utils.getParametersAsEnvVars(item);

        assertEquals(1, env.size());
        assertEquals("ok", env.get("GOOD"));
        assertFalse(env.containsKey("NULL_PARAM"));
    }

    @Test
    void getParametersAsEnvVars_nonStringValueConvertedToString() {
        ParameterValue intParam = mock(ParameterValue.class);
        when(intParam.getName()).thenReturn("NUM");
        when(intParam.getValue()).thenReturn(42);

        ParametersAction action = mock(ParametersAction.class);
        when(action.getParameters()).thenReturn(List.of(intParam));

        Queue.Item item = mock(Queue.Item.class);
        when(item.getActions(ParametersAction.class)).thenReturn(List.of(action));

        EnvVars env = Utils.getParametersAsEnvVars(item);

        assertEquals("42", env.get("NUM"));
    }

    @Test
    void getParametersAsEnvVars_laterActionOverridesEarlier() {
        StringParameterValue p1 = new StringParameterValue("KEY", "first");
        ParametersAction action1 = new ParametersAction(p1);
        StringParameterValue p2 = new StringParameterValue("KEY", "second");
        ParametersAction action2 = new ParametersAction(p2);

        Queue.Item item = mock(Queue.Item.class);
        when(item.getActions(ParametersAction.class)).thenReturn(List.of(action1, action2));

        EnvVars env = Utils.getParametersAsEnvVars(item);

        assertEquals("second", env.get("KEY"));
    }
}
