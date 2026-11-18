package mrnavastar.invsync.sync;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ByteTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.List;

public final class SharedPlayerData {
    public static final String PARTIAL = "InvSyncSpigotPartial";
    public static final String ENDER_ITEMS = "EnderItems";
    private static final List<String> FIELDS =
            List.of("Inventory", "SelectedItemSlot", "equipment", ENDER_ITEMS, "XpLevel", "XpP", "XpTotal");

    private SharedPlayerData() {}

    public static void keepSupportedEnderItems(CompoundTag data, int size) {
        data.getList(ENDER_ITEMS).ifPresent(items -> items.removeIf(item -> isOutsideEnderChest(item, size)));
    }

    public static void preserveUnsupportedEnderItems(CompoundTag data, ListTag previousItems, int size) {
        ListTag items = data.getListOrEmpty(ENDER_ITEMS);
        // Empty slots in the local range are authoritative, too.
        for (Tag item : previousItems) {
            if (isOutsideEnderChest(item, size)) items.add(item.copy());
        }
        data.put(ENDER_ITEMS, items);
    }

    private static boolean isOutsideEnderChest(Tag item, int size) {
        if (!(item instanceof CompoundTag entry)) return false;
        int slot = entry.get("Slot") instanceof ByteTag rawSlot
                ? Byte.toUnsignedInt(rawSlot.value()) : entry.getIntOr("Slot", 0);
        return slot < 0 || slot >= size;
    }

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
