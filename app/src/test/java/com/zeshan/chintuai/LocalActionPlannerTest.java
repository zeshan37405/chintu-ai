package com.zeshan.chintuai;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

public final class LocalActionPlannerTest {
    @Test
    public void plansVideoOpenAndScrollCommandWithoutNetwork() {
        GeminiActionPlan plan = LocalActionPlanner.plan(
                "Wazir Facebook kholo aur scroll karo usko.");

        assertNotNull(plan);
        assertEquals(2, plan.actions.size());
        assertEquals(GeminiActionPlan.Type.OPEN_APP, plan.actions.get(0).type);
        assertEquals("Facebook", plan.actions.get(0).target);
        assertEquals(GeminiActionPlan.Type.SCROLL, plan.actions.get(1).type);
        assertEquals("down", plan.actions.get(1).direction);
    }

    @Test
    public void doesNotClaimSimpleOrUnknownCommands() {
        assertEquals(null, LocalActionPlanner.plan("فیس بک کھولو"));
        assertEquals(null, LocalActionPlanner.plan("مجھے کوئی اچھی تجویز دو"));
    }
}
