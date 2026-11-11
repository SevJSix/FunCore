package me.sevj6.funCore.tycoon.plot;

import me.sevj6.funCore.tycoon.core.template.Vec;
import org.bukkit.entity.Display;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;

/**
 * Makes belt surfaces run. Every surface tile slides one block forward (the way items go), then snaps back and slides
 * again, in a loop. The tiles along a belt are identical, so the snap back can't be seen: the surface looks like it's
 * moving. The client animates each slide itself (transformation interpolation); the server only sends two updates
 * per tile per loop.
 *
 * <p>A loop takes as long as an item takes to cross one block at the belt's speed (times the plot's belt speed
 * upgrade), so the surface and the items on it move together. Fast belts slide two blocks a loop instead, so a loop
 * never gets short enough to strobe.
 */
public final class BeltAnimator {

    private record Tile(Display display, Vector3f direction, double speed) {
    }

    private final List<Tile> tiles = new ArrayList<>();

    /** Animates a surface tile: {@code slide} is its direction, with the belt's speed (blocks a second) as length. */
    public void add(Display display, Vec slide) {
        double speed = Math.sqrt(slide.x() * slide.x() + slide.y() * slide.y() + slide.z() * slide.z());
        if (!(speed > 0)) return;
        tiles.add(new Tile(display, new Vector3f((float) (slide.x() / speed), (float) (slide.y() / speed),
                (float) (slide.z() / speed)), speed));
    }

    public void clear() {
        tiles.clear();
    }

    /** Called every tick: starts each tile's next slide, or snaps it back, when it's due. */
    public void tick(long now, double speedMultiplier) {
        for (Tile tile : tiles) {
            Display display = tile.display();
            if (!display.isValid()) continue;
            // Fast belts slide two blocks a loop, so a loop never gets so short it strobes.
            double blocksPerTick = tile.speed() * speedMultiplier / 20.0;
            int blocks = blocksPerTick > 0.25 ? 2 : 1;
            int period = (int) Math.max(3, Math.round(blocks / blocksPerTick));
            long phase = now % period;
            if (phase == 0) {
                // Back to the start, instantly.
                display.setInterpolationDuration(0);
                display.setTransformation(withTranslation(display.getTransformation(), new Vector3f()));
            } else if (phase == 1) {
                // One block forward, sliding over the rest of the loop.
                display.setInterpolationDelay(0);
                display.setInterpolationDuration(period - 1);
                display.setTransformation(withTranslation(display.getTransformation(), new Vector3f(tile.direction()).mul(blocks)));
            }
        }
    }

    private static Transformation withTranslation(Transformation from, Vector3f translation) {
        return new Transformation(translation, from.getLeftRotation(), from.getScale(), from.getRightRotation());
    }
}
