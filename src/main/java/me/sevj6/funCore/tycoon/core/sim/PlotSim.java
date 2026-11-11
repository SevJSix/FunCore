package me.sevj6.funCore.tycoon.core.sim;

import me.sevj6.funCore.tycoon.core.template.Component;
import me.sevj6.funCore.tycoon.core.template.Template;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;

/**
 * One plot's production, as pure data: owned droppers fire on their intervals, items move along their routes,
 * upgraders raise their value, and collectors pay them out. Driven by {@link #tick(long)} with a tick number; it
 * tells a {@link Listener} what happened, which is where the server shows it.
 *
 * <p>Nothing is checked every tick: dropper fires and items reaching the end of a segment sit in one queue ordered by
 * when they're due, and a tick only takes what's due.
 */
public final class PlotSim {

    /** What the server shows (or does) for each change. All methods are optional. */
    public interface Listener {
        /** A dropper made an item, at its spout. It starts moving next tick. */
        default void spawned(Item item) {
        }

        /** An item started along a segment, arriving {@code segment.ticks()} ticks from now. */
        default void moving(Item item, Segment segment) {
        }

        default void upgraded(Item item, Component.Upgrader upgrader) {
        }

        /** An item reached a collector, worth {@code payout} cents (value times the income multiplier). */
        default void collected(Item item, String collector, double payout) {
        }

        /** An item was thrown away without being paid (the plot was reset). */
        default void discarded(Item item) {
        }
    }

    private record Event(long due, long order, @Nullable Component.Dropper fire, @Nullable Item item)
            implements Comparable<Event> {

        @Override
        public int compareTo(Event other) {
            int byDue = Long.compare(due, other.due);
            return byDue != 0 ? byDue : Long.compare(order, other.order);
        }
    }

    private final Template template;
    private final Listener listener;
    private final Random random;
    private final Set<String> owned = new HashSet<>();
    /** Droppers firing now, each with its route. */
    private final Map<String, List<Segment>> active = new LinkedHashMap<>();
    /** Owned upgraders by the waypoint they sit on. */
    private final Map<Segment.Waypoint, List<Component.Upgrader>> upgraders = new HashMap<>();
    private final Map<Long, Item> items = new LinkedHashMap<>();
    private final PriorityQueue<Event> queue = new PriorityQueue<>();
    private double incomeMultiplier = 1.0;
    private long now;
    private long nextOrder;
    private long nextItemId;

    /** @param startTick the tick it starts at: the first {@link #tick} call should be for the tick after it */
    public PlotSim(Template template, Listener listener, Random random, long startTick) {
        this.template = template;
        this.listener = listener;
        this.random = random;
        this.now = startTick;
    }

    public Template template() {
        return template;
    }

    /** The tick the plot is at (the last one passed to {@link #tick}). */
    public long now() {
        return now;
    }

    public void setIncomeMultiplier(double multiplier) {
        this.incomeMultiplier = multiplier;
    }

    public double incomeMultiplier() {
        return incomeMultiplier;
    }

    /**
     * Adds owned components: new droppers start firing (each at a random point in its first interval, so they don't
     * all fire together) and new upgraders apply to items reaching them from now on. Unknown ids are ignored.
     */
    public void own(Collection<String> ids) {
        for (String id : ids) {
            Component component = template.component(id);
            if (component == null || !owned.add(id)) continue;
            switch (component) {
                case Component.Dropper dropper -> {
                    active.put(id, Routes.of(template, dropper));
                    schedule(now + 1 + random.nextInt(dropper.interval()), dropper, null);
                }
                case Component.Upgrader upgrader -> upgraders
                        .computeIfAbsent(new Segment.Waypoint(upgrader.belt(), upgrader.waypoint()), at -> new ArrayList<>())
                        .add(upgrader);
                default -> {
                }
            }
        }
    }

    public boolean owns(String id) {
        return owned.contains(id);
    }

    public Set<String> owned() {
        return Collections.unmodifiableSet(owned);
    }

    public Collection<Item> items() {
        return Collections.unmodifiableCollection(items.values());
    }

    public int itemCount() {
        return items.size();
    }

    /** Runs everything due up to and including tick {@code tick}. */
    public void tick(long tick) {
        now = tick;
        Event event;
        while ((event = queue.peek()) != null && event.due <= tick) {
            queue.poll();
            if (event.fire != null) fire(event.fire, event.due);
            else if (event.item != null && !event.item.removed) advance(event.item, event.due);
        }
    }

    /** Makes one item now, as if the dropper fired (for testing). Its normal timer carries on as it was. */
    public boolean fireNow(String dropperId) {
        Component.Dropper dropper = template.component(dropperId) instanceof Component.Dropper d ? d : null;
        if (dropper == null || !active.containsKey(dropperId)) return false;
        spawn(dropper, now);
        return true;
    }

    /** Throws every item away unpaid, and forgets every owned component. Used before a rebuild or a rebirth. */
    public void reset() {
        for (Item item : items.values()) {
            item.removed = true;
            listener.discarded(item);
        }
        items.clear();
        queue.clear();
        owned.clear();
        active.clear();
        upgraders.clear();
    }

    private void fire(Component.Dropper dropper, long due) {
        if (!active.containsKey(dropper.id())) return; // no longer owned
        spawn(dropper, due);
        schedule(due + dropper.interval(), dropper, null);
    }

    private void spawn(Component.Dropper dropper, long at) {
        Item item = new Item(nextItemId++, dropper, active.get(dropper.id()));
        items.put(item.id, item);
        listener.spawned(item);
        schedule(at + 1, null, item);
    }

    /** The item reached the end of its segment (or, the first time, starts moving). */
    private void advance(Item item, long at) {
        Segment arrived = item.currentSegment();
        if (arrived != null) {
            switch (arrived.stop()) {
                case Segment.Waypoint waypoint -> {
                    List<Component.Upgrader> here = upgraders.get(waypoint);
                    if (here != null) {
                        for (Component.Upgrader upgrader : here) {
                            if (item.upgrade(upgrader)) listener.upgraded(item, upgrader);
                        }
                    }
                }
                case Segment.Collector collector -> {
                    items.remove(item.id);
                    item.removed = true;
                    listener.collected(item, collector.collector(), item.value * incomeMultiplier);
                    return;
                }
                case null -> {
                }
            }
        }
        item.segment++;
        Segment next = item.currentSegment();
        if (next == null) { // a route always ends at a collector; this is only a safeguard
            items.remove(item.id);
            item.removed = true;
            listener.discarded(item);
            return;
        }
        listener.moving(item, next);
        schedule(at + next.ticks(), null, item);
    }

    private void schedule(long due, Component.@Nullable Dropper fire, @Nullable Item item) {
        queue.add(new Event(due, nextOrder++, fire, item));
    }
}
