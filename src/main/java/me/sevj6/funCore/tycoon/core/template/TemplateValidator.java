package me.sevj6.funCore.tycoon.core.template;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks a {@link Template} for mistakes that would break plots, each reported with the component (or belt) and what's
 * wrong: unknown or circular prerequisites, belts that lead nowhere, droppers and upgraders on belts their
 * prerequisites don't cover, vault components that depend on main-zone belts (which a rebirth wipes), and pads on
 * the same block.
 */
public final class TemplateValidator {

    private final Template template;
    private final List<String> errors = new ArrayList<>();

    private TemplateValidator(Template template) {
        this.template = template;
    }

    public static List<String> validate(Template template) {
        TemplateValidator validator = new TemplateValidator(template);
        validator.run();
        return validator.errors;
    }

    private void run() {
        Map<String, Component> components = template.components();
        for (String id : template.startWith()) {
            Component component = components.get(id);
            if (component == null) {
                errors.add("start-with: there's no component '" + id + "'");
                continue;
            }
            // A run starts with these, so everything they need has to be there from the start too.
            for (String required : component.common().requires()) {
                if (components.containsKey(required) && !template.startWith().contains(required)) {
                    errors.add("start-with: '" + id + "' requires '" + required + "', which isn't in start-with");
                }
            }
        }
        for (Component component : components.values()) {
            for (String required : component.common().requires()) {
                if (!components.containsKey(required)) {
                    errors.add(where(component) + ": requires '" + required + "', which doesn't exist");
                }
            }
            if (component.common().pad() == null && !template.startWith().contains(component.id())) {
                errors.add(where(component) + ": has no pad (only start-with components can go without one)");
            }
        }
        checkCycles();
        for (Belt belt : allBelts()) checkBelt(belt);
        for (Component component : components.values()) {
            switch (component) {
                case Component.Dropper dropper -> checkDropper(dropper);
                case Component.Upgrader upgrader -> checkUpgrader(upgrader);
                case Component.Conveyor conveyor -> checkVaultBelts(conveyor, beltChain(conveyor.belt().id()));
                case Component.Wall ignored -> {
                }
            }
        }
        checkPads();
        checkFeatures();
        checkFootprint();
        checkBarriers();
        checkBeds();
    }

    private static String where(Component component) {
        return component.typeName() + " '" + component.id() + "'";
    }

    private List<Belt> allBelts() {
        List<Belt> belts = new ArrayList<>(template.baseBelts().values());
        for (Component component : template.components().values()) {
            if (component instanceof Component.Conveyor conveyor) belts.add(conveyor.belt());
        }
        return belts;
    }

    /** Prerequisite loops (a needs b, b needs a) can never be bought. */
    private void checkCycles() {
        Map<String, Integer> state = new HashMap<>(); // 1 = visiting, 2 = done
        for (String id : template.components().keySet()) {
            Deque<String> path = new ArrayDeque<>();
            if (findCycle(id, state, path)) return; // one cycle report is enough to fix
        }
    }

    private boolean findCycle(String id, Map<String, Integer> state, Deque<String> path) {
        Integer seen = state.get(id);
        if (seen != null && seen == 2) return false;
        if (seen != null && seen == 1) {
            List<String> loop = new ArrayList<>();
            boolean inLoop = false;
            for (String step : path.reversed()) {
                if (step.equals(id)) inLoop = true;
                if (inLoop) loop.add(step);
            }
            loop.add(id);
            errors.add("component '" + id + "': prerequisites go in a circle: " + String.join(" -> ", loop));
            return true;
        }
        Component component = template.component(id);
        if (component == null) return false;
        state.put(id, 1);
        path.push(id);
        for (String required : component.common().requires()) {
            if (findCycle(required, state, path)) return true;
        }
        path.pop();
        state.put(id, 2);
        return false;
    }

    /**
     * Every belt has to end up at a collector, and every merge has to land on a real waypoint. Each belt reports its
     * own bad end; following the chain from every belt also catches merges that go round in a loop.
     */
    private void checkBelt(Belt belt) {
        switch (belt.end()) {
            case Belt.ToCollector to -> {
                if (!template.collectors().containsKey(to.collector())) {
                    errors.add(where(belt) + ": goes into collector '" + to.collector() + "', which doesn't exist");
                }
            }
            case Belt.Merge merge -> {
                Belt next = template.belt(merge.belt());
                if (next == null) {
                    errors.add(where(belt) + ": merges into belt '" + merge.belt() + "', which doesn't exist");
                } else if (merge.waypoint() < 0 || merge.waypoint() >= next.path().size()) {
                    errors.add(where(belt) + ": merges at waypoint " + merge.waypoint() + " of '" + next.id()
                            + "', which only has waypoints 0 to " + (next.path().size() - 1));
                }
            }
        }
        Set<String> visited = new LinkedHashSet<>();
        Belt current = belt;
        while (current != null) {
            if (!visited.add(current.id())) {
                errors.add(where(belt) + ": merges round in a loop (" + String.join(" -> ", visited) + " -> "
                        + current.id() + ") and never reaches a collector");
                return;
            }
            current = current.end() instanceof Belt.Merge merge ? template.belt(merge.belt()) : null;
        }
    }

    private static String where(Belt belt) {
        return "belt '" + belt.id() + "'";
    }

    /** The belts an item on {@code beltId} travels: that one, then each one it merges into. Empty if it's unknown. */
    private List<String> beltChain(String beltId) {
        List<String> chain = new ArrayList<>();
        Belt belt = template.belt(beltId);
        while (belt != null && !chain.contains(belt.id())) {
            chain.add(belt.id());
            belt = belt.end() instanceof Belt.Merge merge ? template.belt(merge.belt()) : null;
        }
        return chain;
    }

    /** The component and everything it needs, directly or not. */
    private Set<String> withPrerequisites(Component component) {
        Set<String> all = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(component.id());
        while (!queue.isEmpty()) {
            String id = queue.poll();
            if (!all.add(id)) continue;
            Component next = template.component(id);
            if (next != null) queue.addAll(next.common().requires());
        }
        return all;
    }

    private void checkDropper(Component.Dropper dropper) {
        Belt belt = template.belt(dropper.belt());
        if (belt == null) {
            errors.add(where(dropper) + ": drops onto belt '" + dropper.belt() + "', which doesn't exist");
            return;
        }
        if (dropper.waypoint() < 0 || dropper.waypoint() >= belt.path().size()) {
            errors.add(where(dropper) + ": drops onto waypoint " + dropper.waypoint() + " of '" + belt.id()
                    + "', which only has waypoints 0 to " + (belt.path().size() - 1));
        }
        List<String> chain = beltChain(belt.id());
        checkCovered(dropper, chain);
        checkVaultBelts(dropper, chain);
    }

    private void checkUpgrader(Component.Upgrader upgrader) {
        Belt belt = template.belt(upgrader.belt());
        if (belt == null) {
            errors.add(where(upgrader) + ": sits on belt '" + upgrader.belt() + "', which doesn't exist");
            return;
        }
        if (upgrader.waypoint() < 0 || upgrader.waypoint() >= belt.path().size()) {
            errors.add(where(upgrader) + ": sits on waypoint " + upgrader.waypoint() + " of '" + belt.id()
                    + "', which only has waypoints 0 to " + (belt.path().size() - 1));
        }
        checkCovered(upgrader, List.of(belt.id()));
        checkVaultBelts(upgrader, List.of(belt.id()));
    }

    /** Each bought belt something uses must be among its prerequisites, so it can never exist without them. */
    private void checkCovered(Component component, List<String> belts) {
        Set<String> owned = withPrerequisites(component);
        for (String belt : belts) {
            if (template.isBaseBelt(belt) || owned.contains(belt)) continue;
            errors.add(where(component) + ": uses belt '" + belt + "', but doesn't require it (directly or through "
                    + "its prerequisites)");
        }
    }

    /** A rebirth wipes main-zone purchases, so a vault component can't depend on one of their belts. */
    private void checkVaultBelts(Component component, List<String> belts) {
        if (component.common().zone() != Component.Zone.VAULT) return;
        for (String belt : belts) {
            if (template.component(belt) instanceof Component.Conveyor conveyor
                    && conveyor.common().zone() == Component.Zone.MAIN) {
                errors.add(where(component) + ": is in the vault but uses main-zone belt '" + belt
                        + "', which a rebirth removes");
            }
        }
    }

    /** A feature needs the base to say where it goes, and only one component can switch each one on. */
    private void checkFeatures() {
        Map<String, String> seen = new HashMap<>();
        for (Component component : template.components().values()) {
            String feature = component.common().feature();
            if (feature == null) continue;
            String other = seen.putIfAbsent(feature, where(component));
            if (other != null) errors.add(where(component) + ": " + other + " already switches on '" + feature + "'");
            if (feature.equals("gate-lasers") && template.gateLasers() == null) {
                errors.add(where(component) + ": switches on gate-lasers, but the base has no gate-lasers section");
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // The plot's footprint
    // ------------------------------------------------------------------------------------------------------------

    /** Whether a block is inside the footprint: x and z within the plot, y from the floor up to the template's height. */
    private boolean inside(Offset at) {
        Offset size = template.size();
        return at.x() >= 0 && at.x() < size.x() && at.z() >= 0 && at.z() < size.z() && at.y() >= 0 && at.y() < size.y();
    }

    /** A point (a part's corner, say) inside the footprint, edges included. */
    private boolean inside(Vec at) {
        Offset size = template.size();
        return at.x() >= 0 && at.x() <= size.x() && at.z() >= 0 && at.z() <= size.z() && at.y() >= 0 && at.y() <= size.y();
    }

    private void outsideError(String where, String what, Object at) {
        errors.add(where + ": " + what + " " + at + " is outside the plot (x 0-" + (template.size().x() - 1) + ", z 0-"
                + (template.size().z() - 1) + ", dy 0-" + (template.size().y() - 1) + ")");
    }

    /**
     * Everything must be inside the plot's footprint, so it can't land on a neighbour or the road: the base's positions
     * always, and a component's unless it says {@code outside: true}.
     */
    private void checkFootprint() {
        if (!inside(template.spawn())) outsideError("base", "spawn", template.spawn());
        template.collectors().forEach((id, at) -> {
            if (!inside(at)) outsideError("base", "collector '" + id + "'", at);
        });
        if (template.rebirthPad() != null && !inside(template.rebirthPad())) outsideError("base", "rebirth-pad", template.rebirthPad());
        if (template.vaultDoor() != null) {
            for (Offset block : template.vaultDoor().blocks()) {
                if (!inside(block)) {
                    outsideError("base", "vault door block", block);
                    break;
                }
            }
        }
        template.trophySlots().forEach((id, at) -> {
            if (!inside(at)) outsideError("base", "trophy slot '" + id + "'", at);
        });
        if (template.manual() != null) {
            Template.Manual manual = template.manual();
            if (!inside(manual.at())) outsideError("base", "manual (" + manual.name() + ")", manual.at());
            if (!inside(manual.upgrades().pad())) outsideError("base", manual.upgrades().name() + " pad", manual.upgrades().pad());
        }
        if (template.starShower() != null) {
            StarShower stars = template.starShower();
            if (!inside(stars.pad())) outsideError("base", stars.name() + " pad", stars.pad());
            for (Box area : stars.areas()) {
                if (!inside(area.min()) || !inside(area.max())) outsideError("base", stars.name() + " area", area.min());
            }
        }
        if (template.beltSpeed() != null && !inside(template.beltSpeed().pad())) {
            outsideError("base", template.beltSpeed().name() + " pad", template.beltSpeed().pad());
        }
        if (template.gateLasers() != null) {
            Template.GateLasers lasers = template.gateLasers();
            if (!inside(lasers.zone().min()) || !inside(lasers.zone().max())) outsideError("base", "gate-lasers", lasers.zone().min());
            if (!inside(lasers.lever())) outsideError("base", "gate-lasers lever", lasers.lever());
        }
        if (template.gateLabel() != null && !inside(template.gateLabel().at())) {
            outsideError("base", "gate-label", template.gateLabel().at());
        }
        template.baseBelts().values().forEach(belt -> checkWaypointsInside("base belt '" + belt.id() + "'", belt));

        for (Component component : template.components().values()) {
            if (component.common().outside()) continue;
            String where = where(component);
            Offset pad = component.common().pad();
            if (pad != null && !inside(pad)) outsideError(where, "pad", pad);
            for (Offset barrier : component.common().barriers()) {
                if (!inside(barrier)) {
                    outsideError(where, "barrier", barrier);
                    break;
                }
            }
            for (Part part : component.common().parts()) {
                Vec low = part.block() ? part.at() : new Vec(part.at().x() - part.size().x() / 2, part.at().y() - part.size().y() / 2,
                        part.at().z() - part.size().z() / 2);
                Vec high = new Vec(low.x() + part.size().x(), low.y() + part.size().y(), low.z() + part.size().z());
                if (!inside(low) || !inside(high)) {
                    outsideError(where, "part " + part.data(), part.at());
                    break;
                }
            }
            Component.Bees bees = component.common().bees();
            if (bees != null && !inside(bees.home())) outsideError(where, "bees home", bees.home());
            switch (component) {
                case Component.Dropper dropper -> {
                    if (!inside(dropper.spout())) outsideError(where, "spout", dropper.spout());
                }
                case Component.Conveyor conveyor -> checkWaypointsInside(where, conveyor.belt());
                default -> {
                }
            }
        }
    }

    private void checkWaypointsInside(String where, Belt belt) {
        for (Offset waypoint : belt.path()) {
            if (!inside(waypoint)) {
                outsideError(where, "waypoint", waypoint);
                return;
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Barriers
    // ------------------------------------------------------------------------------------------------------------

    /**
     * A barrier can't go where players stand or step (pads, the spawn), in the vault doorway (which has its own), or on
     * another component's barrier: two components sharing one would leave a hole when either goes.
     */
    private void checkBarriers() {
        Map<Offset, String> reserved = new HashMap<>();
        reserved.put(template.spawn(), "the spawn");
        reserved.put(template.spawn().add(0, 1, 0), "the spawn");
        if (template.rebirthPad() != null) reserved.put(template.rebirthPad(), "the rebirth pad");
        if (template.vaultDoor() != null) template.vaultDoor().blocks().forEach(block -> reserved.put(block, "the vault door"));
        if (template.manual() != null) {
            reserved.put(template.manual().at(), "the " + template.manual().name());
            reserved.put(template.manual().upgrades().pad(), "the " + template.manual().upgrades().name() + " pad");
        }
        if (template.beltSpeed() != null) reserved.put(template.beltSpeed().pad(), "the " + template.beltSpeed().name() + " pad");
        if (template.starShower() != null) reserved.put(template.starShower().pad(), "the " + template.starShower().name() + " pad");
        if (template.gateLasers() != null) {
            reserved.put(template.gateLasers().lever(), "the gate-lasers lever");
            template.gateLasers().zone().blocks().forEach(block -> reserved.put(block, "the gate"));
        }
        for (Component component : template.components().values()) {
            Offset pad = component.common().pad();
            if (pad != null) reserved.put(pad, "the pad of " + where(component));
        }
        Map<Offset, String> barriers = new HashMap<>();
        for (Component component : template.components().values()) {
            Set<String> reported = new HashSet<>();
            for (Offset barrier : component.common().barriers()) {
                String clash = reserved.get(barrier);
                if (clash == null) {
                    String other = barriers.putIfAbsent(barrier, where(component));
                    if (other != null && !other.equals(where(component))) clash = "a barrier of " + other;
                }
                if (clash != null && reported.add(clash)) {
                    errors.add(where(component) + ": its barrier " + barrier + " is on " + clash);
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------------------------
    // Belt beds
    // ------------------------------------------------------------------------------------------------------------

    /**
     * With beds listed, every belt has to run along them: each waypoint, each straight run between waypoints, and the
     * run into its collector or the belt it merges into. Collectors have to be on a bed too.
     */
    private void checkBeds() {
        if (template.beds().isEmpty()) return;
        template.collectors().forEach((id, at) -> {
            if (!onBed(at)) errors.add("base: collector '" + id + "' " + at + " isn't on a belt bed");
        });
        for (Belt belt : allBelts()) {
            List<Offset> path = belt.path();
            List<Offset> blocks = new ArrayList<>();
            for (int i = 0; i + 1 < path.size(); i++) blocks.addAll(Box.line(path.get(i), path.get(i + 1), true));
            blocks.add(path.getLast());
            Offset end = switch (belt.end()) {
                case Belt.ToCollector to -> template.collectors().get(to.collector());
                case Belt.Merge merge -> {
                    Belt next = template.belt(merge.belt());
                    yield next == null || merge.waypoint() < 0 || merge.waypoint() >= next.path().size() ? null
                            : next.path().get(merge.waypoint());
                }
            };
            if (end != null) blocks.addAll(Box.line(path.getLast(), end, true));
            for (Offset block : blocks) {
                if (!onBed(block)) {
                    String owner = template.isBaseBelt(belt.id()) ? "base belt '" + belt.id() + "'" : where(template.component(belt.id()));
                    errors.add(owner + ": runs over " + block + ", which isn't on a belt bed");
                    break;
                }
            }
        }
    }

    private boolean onBed(Offset at) {
        for (Box bed : template.beds()) {
            if (bed.contains(at)) return true;
        }
        return false;
    }

    private void checkPads() {
        Map<Offset, String> taken = new HashMap<>();
        if (template.rebirthPad() != null) taken.put(template.rebirthPad(), "the rebirth pad");
        if (template.manual() != null) {
            Template.Manual manual = template.manual();
            taken.put(manual.at(), "the " + manual.name());
            String upgrades = "the " + manual.upgrades().name() + " pad";
            String clash = taken.putIfAbsent(manual.upgrades().pad(), upgrades);
            if (clash != null) errors.add("base: " + upgrades + " " + manual.upgrades().pad() + " is on the same block as " + clash);
        }
        if (template.starShower() != null) {
            String stars = "the " + template.starShower().name() + " pad";
            String clash = taken.putIfAbsent(template.starShower().pad(), stars);
            if (clash != null) errors.add("base: " + stars + " " + template.starShower().pad() + " is on the same block as " + clash);
        }
        if (template.beltSpeed() != null) {
            String speed = "the " + template.beltSpeed().name() + " pad";
            String clash = taken.putIfAbsent(template.beltSpeed().pad(), speed);
            if (clash != null) errors.add("base: " + speed + " " + template.beltSpeed().pad() + " is on the same block as " + clash);
        }
        for (Component component : template.components().values()) {
            Offset pad = component.common().pad();
            if (pad == null) continue;
            String other = taken.putIfAbsent(pad, where(component));
            if (other != null) errors.add(where(component) + ": its pad " + pad + " is on the same block as the pad of " + other);
        }
    }
}
