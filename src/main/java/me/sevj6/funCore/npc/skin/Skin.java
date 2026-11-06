package me.sevj6.funCore.npc.skin;

import com.destroystokyo.paper.profile.ProfileProperty;
import io.papermc.paper.datacomponent.item.ResolvableProfile;
import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * A player skin as signed by Mojang's session servers.
 *
 * @param ownerName name of the account the skin came from (for display; it can go stale if they rename)
 * @param ownerId   UUID of the account the skin came from
 * @param value     Base64 {@code textures} property value
 * @param signature Mojang's signature for {@code value}. The client won't load the skin without it.
 */
public record Skin(String ownerName, UUID ownerId, String value, String signature) {

    public ProfileProperty toProperty() {
        return new ProfileProperty("textures", value, signature);
    }

    /** A profile that can be applied to a Mannequin to give it this skin. */
    public ResolvableProfile toProfile() {
        return ResolvableProfile.resolvableProfile()
                .uuid(ownerId)
                .name(ownerName)
                .addProperty(toProperty())
                .build();
    }

    public void write(ConfigurationSection section) {
        section.set("owner-name", ownerName);
        section.set("owner-id", ownerId.toString());
        section.set("value", value);
        section.set("signature", signature);
    }

    /** Reads a skin saved by {@link #write}, or {@code null} if the section is missing or incomplete. */
    public static @Nullable Skin read(@Nullable ConfigurationSection section) {
        if (section == null) return null;
        String name = section.getString("owner-name");
        String id = section.getString("owner-id");
        String value = section.getString("value");
        String signature = section.getString("signature");
        if (name == null || id == null || value == null || signature == null) return null;
        try {
            return new Skin(name, UUID.fromString(id), value, signature);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
