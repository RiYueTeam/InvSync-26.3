package mrnavastar.invsync.sync;

import net.minecraft.nbt.CompoundTag;

import java.util.List;

public final class SharedPlayerData {
    public static final String PARTIAL = "InvSyncSpigotPartial";
    public static final String ENDER_ITEMS = "EnderItems";
    private static final List<String> FIELDS =
            List.of("Inventory", "SelectedItemSlot", "equipment", ENDER_ITEMS, "XpLevel", "XpP", "XpTotal");

    private SharedPlayerData() {}

    public static void copyFields(CompoundTag source, CompoundTag target) {
        for (String field : FIELDS) {
            var value = source.get(field);
            if (value == null) {
                // Older Spigot-only snapshots did not capture ender chests.
                if (field.equals(ENDER_ITEMS) && source.getBooleanOr(PARTIAL, false)) continue;
                target.remove(field);
            } else {
                target.put(field, value.copy());
            }
        }
    }
}
