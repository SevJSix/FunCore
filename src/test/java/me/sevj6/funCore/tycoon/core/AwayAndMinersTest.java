package me.sevj6.funCore.tycoon.core;

import me.sevj6.funCore.tycoon.core.data.PlayerData;
import me.sevj6.funCore.tycoon.core.data.PlayerDataCodec;
import me.sevj6.funCore.tycoon.core.template.Component;
import me.sevj6.funCore.tycoon.core.template.Offset;
import me.sevj6.funCore.tycoon.core.template.Template;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Earning while away, More Miners!, and the roof sealing the plot. */
class AwayAndMinersTest {

    @Test
    void awayEarningsAreTheRateForTheTimeUpToTheCap() {
        // $1 a second (100 cents), away an hour: $3,600
        assertEquals(360_000, AwayEarnings.earned(100, 3_600_000, 1.0, 2));
        // away a day, but only 2 hours count
        assertEquals(720_000, AwayEarnings.earned(100, 86_400_000, 1.0, 2));
        // half the rate
        assertEquals(180_000, AwayEarnings.earned(100, 3_600_000, 0.5, 2));
        assertEquals(0, AwayEarnings.earned(0, 3_600_000, 1.0, 2));
        assertEquals(0, AwayEarnings.earned(100, -5, 1.0, 2));
    }

    @Test
    void whenTheyLeftIsSaved() {
        PlayerData data = new PlayerData();
        data.leftAt(1_762_800_000_000L, 1234.5);
        PlayerData back = PlayerDataCodec.fromMap(PlayerDataCodec.toMap(data));
        assertEquals(1_762_800_000_000L, back.lastSeen());
        assertEquals(1234.5, back.awayRate(), 1e-9);
    }

    @Test
    void moreMinersAddsFourVeinsOnTheCornerDots() {
        Template template = TestTemplates.sample().template();
        assertEquals(List.of(new Offset(4, 1, 4), new Offset(36, 1, 4), new Offset(4, 1, 40), new Offset(36, 1, 40)),
                template.manual().extra());
        assertEquals("more-miners", template.withFeature("more-miners").id());
        // each extra vein looks just like the main one, moved
        Template.Manual vein = template.manual();
        assertEquals(vein.parts().size(), vein.partsAt(new Offset(4, 1, 4)).size());
        assertEquals(4.0, vein.partsAt(new Offset(4, 1, 4)).getFirst().at().x(), 1e-9);
    }

    @Test
    void theRoofIsSolidAndTheWallsReachIt() {
        Component roof = TestTemplates.sample().template().component("plot-roof");
        List<Offset> barriers = roof.common().barriers();
        assertTrue(barriers.contains(new Offset(20, 7, 22)), "the roof itself");
        assertTrue(barriers.contains(new Offset(20, 6, 44)) && barriers.contains(new Offset(20, 5, 44)), "over the gate");
        assertTrue(barriers.contains(new Offset(0, 6, 0)), "the corners");
        assertTrue(barriers.contains(new Offset(40, 5, 30)), "the sides");
    }
}
