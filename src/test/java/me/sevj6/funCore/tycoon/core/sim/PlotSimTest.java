package me.sevj6.funCore.tycoon.core.sim;

import me.sevj6.funCore.tycoon.core.TestTemplates;
import me.sevj6.funCore.tycoon.core.template.Component;
import me.sevj6.funCore.tycoon.core.template.Template;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlotSimTest {

    /** Records what the simulation reports. */
    private static final class Recorder implements PlotSim.Listener {
        int spawned, upgraded, discarded;
        final List<Double> payouts = new ArrayList<>();
        final List<Long> spawnTicks = new ArrayList<>();
        PlotSim sim;

        @Override
        public void spawned(Item item) {
            spawned++;
            spawnTicks.add(sim.now());
        }

        @Override
        public void upgraded(Item item, Component.Upgrader upgrader) {
            upgraded++;
        }

        @Override
        public void collected(Item item, String collector, double payout) {
            payouts.add(payout);
        }

        @Override
        public void discarded(Item item) {
            discarded++;
        }
    }

    private static PlotSim sim(Template template, Recorder recorder, long start) {
        PlotSim sim = new PlotSim(template, recorder, new Random(42), start);
        recorder.sim = sim;
        sim.own(template.startWith());
        return sim;
    }

    private static void run(PlotSim sim, long from, long to) {
        for (long tick = from; tick <= to; tick++) sim.tick(tick);
    }

    @Test
    void dropperItemsReachTheCollectorAndPay() {
        Template template = TestTemplates.valid(TestTemplates.simple(""));
        Recorder recorder = new Recorder();
        PlotSim sim = sim(template, recorder, 1000);
        run(sim, 1001, 1000 + 20 * 50); // 50 seconds
        // one item a second; each takes about 5 s to arrive
        assertTrue(recorder.spawned >= 49 && recorder.spawned <= 51, "spawned " + recorder.spawned);
        assertTrue(recorder.payouts.size() >= recorder.spawned - 6, "paid " + recorder.payouts.size());
        recorder.payouts.forEach(payout -> assertEquals(100.0, payout, 1e-9));
        assertEquals(recorder.spawned - recorder.payouts.size(), sim.itemCount());
    }

    @Test
    void startsFromItsStartTickWithoutCatchingUp() {
        Template template = TestTemplates.valid(TestTemplates.simple(""));
        Recorder recorder = new Recorder();
        PlotSim sim = sim(template, recorder, 1_000_000);
        run(sim, 1_000_001, 1_000_020);
        assertEquals(1, recorder.spawned, "one interval passed, so one item, not a backlog from tick 0");
    }

    @Test
    void firesOnItsInterval() {
        Template template = TestTemplates.valid(TestTemplates.simple(""));
        Recorder recorder = new Recorder();
        PlotSim sim = sim(template, recorder, 0);
        run(sim, 1, 200);
        for (int i = 1; i < recorder.spawnTicks.size(); i++) {
            assertEquals(20, recorder.spawnTicks.get(i) - recorder.spawnTicks.get(i - 1));
        }
    }

    @Test
    void upgradersStackAndApplyOncePerItem() {
        Template template = TestTemplates.valid(TestTemplates.simple("""
                double:
                  type: upgrader
                  requires: [belt]
                  pad: [5, 1, 5]
                  at: { belt: belt, waypoint: 1 }
                  multiplier: 2
                triple:
                  type: upgrader
                  requires: [belt]
                  pad: [6, 1, 5]
                  at: { belt: belt, waypoint: 2 }
                  multiplier: 3
                """));
        Recorder recorder = new Recorder();
        PlotSim sim = sim(template, recorder, 0);
        sim.own(List.of("double", "triple"));
        sim.setIncomeMultiplier(1.5);
        run(sim, 1, 400);
        assertTrue(!recorder.payouts.isEmpty());
        // $1 x 2 x 3, then the 1.5x income multiplier
        recorder.payouts.forEach(payout -> assertEquals(100.0 * 2 * 3 * 1.5, payout, 1e-9));
        // every paid item went through both, once each
        assertTrue(recorder.upgraded >= recorder.payouts.size() * 2, "upgrades " + recorder.upgraded);
        assertTrue(recorder.upgraded <= (recorder.payouts.size() + sim.itemCount()) * 2, "upgrades " + recorder.upgraded);
    }

    @Test
    void unownedUpgradersDoNothing() {
        Template template = TestTemplates.valid(TestTemplates.simple("""
                double:
                  type: upgrader
                  requires: [belt]
                  pad: [5, 1, 5]
                  at: { belt: belt, waypoint: 1 }
                  multiplier: 2
                """));
        Recorder recorder = new Recorder();
        PlotSim sim = sim(template, recorder, 0);
        run(sim, 1, 300);
        assertEquals(0, recorder.upgraded);
        recorder.payouts.forEach(payout -> assertEquals(100.0, payout, 1e-9));
    }

    @Test
    void resetDiscardsEverythingInFlight() {
        Template template = TestTemplates.valid(TestTemplates.simple(""));
        Recorder recorder = new Recorder();
        PlotSim sim = sim(template, recorder, 0);
        run(sim, 1, 70);
        int inFlight = sim.itemCount();
        assertTrue(inFlight > 0);
        sim.reset();
        assertEquals(inFlight, recorder.discarded);
        assertEquals(0, sim.itemCount());
        int paid = recorder.payouts.size();
        run(sim, 71, 400);
        assertEquals(paid, recorder.payouts.size(), "nothing is owned any more, so nothing more is made or paid");
    }

    @Test
    void fireNowMakesAnExtraItem() {
        Template template = TestTemplates.valid(TestTemplates.simple(""));
        Recorder recorder = new Recorder();
        PlotSim sim = sim(template, recorder, 0);
        assertTrue(sim.fireNow("dropper"));
        assertEquals(1, recorder.spawned);
        assertTrue(!sim.fireNow("belt"), "only droppers fire");
    }
}
