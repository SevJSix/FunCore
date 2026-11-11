package me.sevj6.funCore.tycoon.core;

import me.sevj6.funCore.tycoon.core.template.Template;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PurchasesTest {

    private final Template template = TestTemplates.valid(TestTemplates.simple("""
            fancy:
              type: wall
              price: 10
              requires: [dropper]
              pad: [5, 1, 5]
            vaulty:
              type: wall
              zone: vault
              min-rebirths: 2
              price: 10
              pad: [6, 1, 5]
            """));

    private Purchases.Result check(String id, Set<String> owned, int rebirths, long balance) {
        return Purchases.check(template, id, owned, rebirths, balance);
    }

    @Test
    void buyableWithTheMoney() {
        assertEquals(Purchases.Result.OK, check("fancy", Set.of("belt", "dropper"), 0, 1000));
        assertEquals(Purchases.Result.OK, check("fancy", Set.of("belt", "dropper"), 0, 1000), "exactly the price is enough");
    }

    @Test
    void checksInOrder() {
        assertEquals(Purchases.Result.UNKNOWN, check("ghost", Set.of(), 0, 0));
        assertEquals(Purchases.Result.ALREADY_OWNED, check("fancy", Set.of("dropper", "fancy"), 0, 0));
        // missing a prerequisite wins over being too poor
        assertEquals(Purchases.Result.MISSING_PREREQUISITE, check("fancy", Set.of("belt"), 0, 0));
        // not enough rebirths wins over being too poor
        assertEquals(Purchases.Result.NEEDS_REBIRTHS, check("vaulty", Set.of(), 1, 0));
        assertEquals(Purchases.Result.TOO_POOR, check("vaulty", Set.of(), 2, 999));
        assertEquals(Purchases.Result.OK, check("vaulty", Set.of(), 2, 1000));
    }
}
