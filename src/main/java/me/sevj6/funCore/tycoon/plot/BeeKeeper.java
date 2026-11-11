package me.sevj6.funCore.tycoon.plot;

import me.sevj6.funCore.tycoon.core.template.Component;
import org.bukkit.Location;
import org.bukkit.entity.Bee;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Real bees living on a component (a tree's nest). One at a time they come out of the nest, buzz around within range
 * of it on their own (vanilla bee flight), and when their time out is up they fly back to the nest and are gone, while
 * new ones come out. Any that stray too far are steered back toward the nest. They're never saved with the world and
 * go with the component (see {@link Fixtures}).
 */
public final class BeeKeeper {

    /** How long a bee stays out, in ticks (picked between these). */
    private static final int MIN_OUT = 20 * 20, MAX_OUT = 40 * 20;
    /** Gap between bees coming out, in ticks (picked between these). */
    private static final int MIN_GAP = 3 * 20, MAX_GAP = 7 * 20;
    /** How long a bee gets to fly home before it simply goes, in ticks. */
    private static final int HOME_WITHIN = 15 * 20;
    private static final double SPEED = 1.0;

    private static final class Out {
        final Bee bee;
        final long leaveAt;
        long homeBy = -1;

        Out(Bee bee, long leaveAt) {
            this.bee = bee;
            this.leaveAt = leaveAt;
        }
    }

    private final Fixtures fixtures;
    private final String group;
    private final Component.Bees bees;
    private final Location home;
    private final List<Out> out = new ArrayList<>();
    private long nextOut;

    public BeeKeeper(Fixtures fixtures, String group, Component.Bees bees) {
        this.fixtures = fixtures;
        this.group = group;
        this.bees = bees;
        this.home = fixtures.at(bees.home());
    }

    /** Called every tick; does its work every half second. */
    public void tick(long now) {
        if (now % 10 != 0) return;
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (Iterator<Out> it = out.iterator(); it.hasNext(); ) {
            Out entry = it.next();
            Bee bee = entry.bee;
            if (!bee.isValid()) {
                it.remove();
                continue;
            }
            double distance = bee.getLocation().distance(home);
            if (distance > bees.range() + 6) {
                // Got well away (through a gap, say): it's gone, as if it went home; another comes out later.
                bee.remove();
                it.remove();
                continue;
            }
            if (entry.homeBy >= 0) {
                // Going in: gone once it reaches the nest (or it's taking too long).
                if (distance < 1.3 || now >= entry.homeBy) {
                    bee.remove();
                    it.remove();
                } else {
                    bee.getPathfinder().moveTo(home, SPEED);
                }
            } else if (now >= entry.leaveAt) {
                entry.homeBy = now + HOME_WITHIN;
                bee.getPathfinder().moveTo(home, SPEED);
            } else if (distance > bees.range()) {
                // Strayed: back toward the nest, somewhere around it.
                double angle = random.nextDouble(Math.PI * 2), radius = random.nextDouble(bees.range() / 2);
                bee.getPathfinder().moveTo(home.clone().add(Math.cos(angle) * radius, random.nextDouble(-1, 2),
                        Math.sin(angle) * radius), SPEED);
            }
        }
        if (out.size() < bees.count() && now >= nextOut) {
            Bee bee = fixtures.spawn(group, bees.home(), Bee.class, created -> {
                created.setInvulnerable(true);
                created.setAnger(0);
                created.setCannotEnterHiveTicks(Integer.MAX_VALUE); // there's no real hive for it to look for
                created.setRemoveWhenFarAway(false);
            });
            out.add(new Out(bee, now + random.nextInt(MIN_OUT, MAX_OUT)));
            nextOut = now + random.nextInt(MIN_GAP, MAX_GAP);
        }
    }
}
