package me.sevj6.funCore.tycoon.plot;

import me.sevj6.funCore.tycoon.core.template.Offset;
import me.sevj6.funCore.tycoon.core.template.Part;
import me.sevj6.funCore.tycoon.core.template.Template;
import me.sevj6.funCore.tycoon.core.template.Vec;
import me.sevj6.funCore.util.Utils;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Location;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

/**
 * One of the map's plots, owned or not, and what the plugin has added to it (see {@link Fixtures}). Whatever belongs
 * to the plot itself rather than to an owner lives here: the vault door's seal, which is up whenever nobody owns the
 * plot.
 */
public final class PlotSlot {

    /** Group names in {@link Fixtures}. */
    public static final String DOOR = "vault-door";

    private final int index;
    private final Location origin;
    private final Fixtures fixtures;

    public PlotSlot(int index, Location origin, Fixtures fixtures) {
        this.index = index;
        this.origin = origin.clone();
        this.fixtures = fixtures;
    }

    /** 0-based; players see {@code index + 1}. */
    public int index() {
        return index;
    }

    public Location origin() {
        return origin.clone();
    }

    public Fixtures fixtures() {
        return fixtures;
    }

    /**
     * Closes the vault's doorway: barriers in every doorway block (the actual wall), iron bars over them so it looks
     * locked, and a sign saying how many rebirths open it. Does nothing if it's already sealed.
     */
    public void sealVault(Template template) {
        Template.VaultDoor door = template.vaultDoor();
        if (door == null || fixtures.has(DOOR)) return;
        List<Part> bars = new ArrayList<>();
        for (Offset block : door.blocks()) {
            fixtures.place(DOOR, block, Material.BARRIER);
            bars.add(Part.block("minecraft:iron_bars", block.x(), block.y(), block.z(), 1, 1, 1));
        }
        fixtures.spawn(DOOR, bars);
        // On the outside of the door, just above it: the doorway's middle, on the side away from the vault.
        Offset min = door.min(), max = door.max();
        Vec label = new Vec((min.x() + max.x() + 1) / 2.0, max.y() + 0.6, max.z() + 1.3);
        fixtures.label(DOOR, label, Utils.mm("<gold><bold>Rebirth Vault</bold></gold>\n<gray>Requires Rebirth <n>",
                Placeholder.unparsed("n", String.valueOf(door.unlockRebirths()))));
    }

    /** Opens the vault's doorway. */
    public void openVault() {
        fixtures.remove(DOOR);
    }
}
