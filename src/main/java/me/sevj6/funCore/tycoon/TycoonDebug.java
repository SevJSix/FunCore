package me.sevj6.funCore.tycoon;

import me.sevj6.funCore.tycoon.core.template.Belt;
import me.sevj6.funCore.tycoon.core.template.Box;
import me.sevj6.funCore.tycoon.core.template.Component;
import me.sevj6.funCore.tycoon.core.template.Offset;
import me.sevj6.funCore.tycoon.core.template.Template;
import me.sevj6.funCore.tycoon.core.template.Vec;
import me.sevj6.funCore.tycoon.plot.PlotSlot;
import me.sevj6.funCore.util.Utils;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.entity.Player;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * {@code /tycoon admin debug}: shows an admin, with particles only they see, where everything in the template lands on
 * the map. Every plot's footprint is outlined (to check the configured plot origins line up with the pasted map), and
 * the plot they're standing in shows every position the template gives it. Refreshed twice a second for a minute.
 */
final class TycoonDebug {

    private static final long DURATION_TICKS = 60 * 20;
    private static final int EVERY_TICKS = 10;

    // Colours, as the legend in chat names them.
    private static final Color OUTLINE = Color.WHITE, PAD = Color.LIME, SPOUT = Color.YELLOW, BELT = Color.AQUA,
            WAYPOINT = Color.BLUE, UPGRADER = Color.FUCHSIA, COLLECTOR = Color.ORANGE, BED = Color.GRAY,
            SPAWN = Color.fromRGB(255, 255, 160), DOOR = Color.RED, TROPHY = Color.PURPLE, SIGN = Color.fromRGB(255, 140, 0),
            BARRIER = Color.MAROON;

    private final TycoonManager tycoon;
    /** Who's watching, and the tick it stops. */
    private final Map<UUID, Long> viewers = new HashMap<>();

    TycoonDebug(TycoonManager tycoon) {
        this.tycoon = tycoon;
    }

    /** Turns it on for a minute (from {@code now}). */
    void start(Player player, long now) {
        viewers.put(player.getUniqueId(), now + DURATION_TICKS);
        player.sendMessage(Utils.prefixed(Utils.mm("<gray>Debug view on for 60 s (<white>/tycoon admin debug off</white> to stop). "
                + "Every plot is outlined in <white>white</white>; the plot you stand in shows: <green>pads</green>, "
                + "<yellow>spouts</yellow>, <aqua>belt paths</aqua> with <blue>waypoints</blue>, <light_purple>upgraders</light_purple>, "
                + "<gold>collectors</gold>, <dark_gray>beds</dark_gray>, <#ffffa0>spawn</#ffffa0>, <red>vault door</red>, "
                + "<dark_purple>trophy slots</dark_purple>, <#ff8c00>gate sign</#ff8c00> and <dark_red>barriers</dark_red>.")));
    }

    boolean stop(Player player) {
        return viewers.remove(player.getUniqueId()) != null;
    }

    void tick(long now) {
        if (viewers.isEmpty() || now % EVERY_TICKS != 0) return;
        Iterator<Map.Entry<UUID, Long>> entries = viewers.entrySet().iterator();
        while (entries.hasNext()) {
            Map.Entry<UUID, Long> entry = entries.next();
            Player player = Bukkit.getPlayer(entry.getKey());
            if (player == null || now >= entry.getValue()) {
                entries.remove();
                if (player != null) player.sendMessage(Utils.prefixed(Utils.mm("<gray>Debug view off.")));
                continue;
            }
            if (tycoon.isTycoonWorld(player.getWorld())) draw(player);
        }
    }

    private void draw(Player player) {
        Template template = tycoon.template();
        if (template == null) return;
        Offset size = template.size();
        PlotSlot standingIn = null;
        for (PlotSlot plot : tycoon.plots()) {
            Location origin = plot.origin();
            outline(player, origin, size);
            double x = player.getLocation().getX() - origin.getX(), z = player.getLocation().getZ() - origin.getZ();
            if (x >= 0 && x < size.x() && z >= 0 && z < size.z()) standingIn = plot;
        }
        if (standingIn != null) details(player, standingIn.origin(), template);
    }

    /** The footprint's edge, a little above the floor. */
    private static void outline(Player player, Location origin, Offset size) {
        double y = 1.1;
        for (double x = 0; x <= size.x(); x += 1) {
            dot(player, origin, new Vec(x, y, 0), OUTLINE, 1.2f);
            dot(player, origin, new Vec(x, y, size.z()), OUTLINE, 1.2f);
        }
        for (double z = 0; z <= size.z(); z += 1) {
            dot(player, origin, new Vec(0, y, z), OUTLINE, 1.2f);
            dot(player, origin, new Vec(size.x(), y, z), OUTLINE, 1.2f);
        }
    }

    private void details(Player player, Location origin, Template template) {
        for (Box bed : template.beds()) {
            for (Offset block : bed.blocks()) dot(player, origin, block.top(), BED, 0.7f);
        }
        dot(player, origin, template.spawn().center(), SPAWN, 2f);
        template.collectors().values().forEach(at -> dot(player, origin, at.top(), COLLECTOR, 2.2f));
        if (template.rebirthPad() != null) dot(player, origin, padPoint(template.rebirthPad()), PAD, 2f);
        if (template.vaultDoor() != null) template.vaultDoor().blocks().forEach(block -> dot(player, origin, block.center(), DOOR, 1.2f));
        template.trophySlots().values().forEach(at -> dot(player, origin, at.center(), TROPHY, 1.6f));
        if (template.gateLabel() != null) dot(player, origin, template.gateLabel().point(), SIGN, 2f);

        template.baseBelts().values().forEach(belt -> belt(player, origin, template, belt));
        for (Component component : template.components().values()) {
            Offset pad = component.common().pad();
            if (pad != null) dot(player, origin, padPoint(pad), PAD, 1.6f);
            for (Offset barrier : component.common().barriers()) dot(player, origin, barrier.center(), BARRIER, 0.8f);
            switch (component) {
                case Component.Dropper dropper -> dot(player, origin, dropper.spout().center(), SPOUT, 1.8f);
                case Component.Conveyor conveyor -> belt(player, origin, template, conveyor.belt());
                case Component.Upgrader upgrader -> {
                    Belt belt = template.belt(upgrader.belt());
                    if (belt != null && upgrader.waypoint() >= 0 && upgrader.waypoint() < belt.path().size()) {
                        Vec at = belt.path().get(upgrader.waypoint()).top();
                        for (double up = 0; up <= 3; up += 0.5) dot(player, origin, new Vec(at.x(), at.y() + up, at.z()), UPGRADER, 1.4f);
                    }
                }
                case Component.Wall ignored -> {
                }
            }
        }
    }

    /** A belt's path where items ride, waypoints marked, including the run to where it ends. */
    private static void belt(Player player, Location origin, Template template, Belt belt) {
        List<Offset> path = belt.path();
        for (int i = 0; i < path.size(); i++) {
            dot(player, origin, path.get(i).top(), WAYPOINT, 1.6f);
            if (i + 1 < path.size()) line(player, origin, path.get(i).top(), path.get(i + 1).top());
        }
        Offset end = switch (belt.end()) {
            case Belt.ToCollector to -> template.collectors().get(to.collector());
            case Belt.Merge merge -> {
                Belt next = template.belt(merge.belt());
                yield next == null || merge.waypoint() >= next.path().size() ? null : next.path().get(merge.waypoint());
            }
        };
        if (end != null) line(player, origin, path.getLast().top(), end.top());
    }

    private static void line(Player player, Location origin, Vec from, Vec to) {
        double length = from.distance(to);
        for (double d = 0; d <= length; d += 0.5) {
            dot(player, origin, from.lerp(to, length == 0 ? 0 : d / length), BELT, 0.8f);
        }
    }

    /** Just above a pad's pressure plate. */
    private static Vec padPoint(Offset pad) {
        return new Vec(pad.x() + 0.5, pad.y() + 0.2, pad.z() + 0.5);
    }

    /** One particle, forced so it shows past the usual 32 blocks (to see far plots' outlines). */
    private static void dot(Player player, Location origin, Vec at, Color colour, float size) {
        player.spawnParticle(Particle.DUST, new Location(origin.getWorld(), origin.getX() + at.x(),
                origin.getY() + at.y(), origin.getZ() + at.z()), 1, 0, 0, 0, 0, new Particle.DustOptions(colour, size), true);
    }
}
