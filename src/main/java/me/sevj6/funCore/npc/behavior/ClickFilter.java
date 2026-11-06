package me.sevj6.funCore.npc.behavior;

import me.sevj6.funCore.npc.event.NpcInteractEvent;

import java.util.Locale;

/** Which clicks a behaviour reacts to. Written to config as {@code click: any|left|right}. */
public enum ClickFilter {
    ANY,
    LEFT,
    RIGHT;

    public boolean matches(NpcInteractEvent.Click click) {
        return this == ANY || name().equals(click.name());
    }

    public static ClickFilter parse(String value) {
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("click must be any, left or right, not '" + value + "'");
        }
    }

    public String configName() {
        return name().toLowerCase(Locale.ROOT);
    }
}
