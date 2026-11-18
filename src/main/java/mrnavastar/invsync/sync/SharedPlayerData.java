package mrnavastar.invsync.sync;

import net.minecraft.nbt.CompoundTag;

import java.util.List;

public final class SharedPlayerData {
    public static final String PARTIAL = "InvSyncSpigotPartial";
    private static final List<String> FIELDS =
            List.of("Inventory", "SelectedItemSlot", "equipment", "XpLevel", "XpP", "XpTotal");

    private SharedPlayerData() {}

    public static void copyFields(CompoundTag source, CompoundTag target) {
        for (String field : FIELDS) {
            var value = source.get(field);
            if (value == null) {
                target.remove(field);
            } else {
                target.put(field, value.copy());
            }
        }
    }
}
