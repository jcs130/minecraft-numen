package com.dwinovo.numen.api.brain;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.DoubleTag;
import net.minecraft.nbt.ListTag;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ResidentAdoptionTest {
    private static final UUID ID = UUID.fromString("8e7b99e0-595d-3402-935c-7139ac6814e3");
    private CompoundTag save() {
        var tag = new CompoundTag();
        tag.putUUID("UUID", ID);
        tag.putFloat("Health", 20);
        tag.putString("Dimension", "minecraft:overworld");
        var bukkit = new CompoundTag(); bukkit.putString("lastKnownName", "ag_Kirito"); tag.put("bukkit", bukkit);
        var pos = new ListTag(); for (double p : new double[]{-545.01, 67, -440.52}) pos.add(DoubleTag.valueOf(p)); tag.put("Pos", pos);
        tag.putString("Inventory", "opaque original inventory including custom items");
        return tag;
    }
    @Test void validatesWithoutChangingTheOriginalSaveOrInventory() {
        var tag = save(); var before = tag.copy();
        var result = ResidentAdoption.inspect(tag, ID, "ag_Kirito");
        assertEquals(-546, result.position().getX()); assertEquals(-441, result.position().getZ());
        assertEquals("minecraft:overworld", result.dimension()); assertEquals(before, tag);
    }
    @Test void refusesWrongUuid() { assertThrows(IllegalArgumentException.class, () -> ResidentAdoption.inspect(save(), UUID.randomUUID(), "ag_Kirito")); }
    @Test void refusesWrongName() { assertThrows(IllegalArgumentException.class, () -> ResidentAdoption.inspect(save(), ID, "ag_Other")); }
    @Test void refusesMissingIdentity() { var t=save(); t.remove("UUID"); assertThrows(IllegalArgumentException.class, () -> ResidentAdoption.inspect(t, ID, "ag_Kirito")); }
    @Test void refusesAlreadyOwnedSave() { var t=save(); t.putUUID("NumenOwner", UUID.randomUUID()); assertThrows(IllegalArgumentException.class, () -> ResidentAdoption.inspect(t, ID, "ag_Kirito")); }
    @Test void refusesDeadSave() { var t=save(); t.putFloat("Health", 0); assertThrows(IllegalArgumentException.class, () -> ResidentAdoption.inspect(t, ID, "ag_Kirito")); }
    @Test void refusesInvalidPosition() { var t=save(); t.getList("Pos", 6).set(0, DoubleTag.valueOf(Double.NaN)); assertThrows(IllegalArgumentException.class, () -> ResidentAdoption.inspect(t, ID, "ag_Kirito")); }
    @Test void refusesMissingDimension() { var t=save(); t.remove("Dimension"); assertThrows(IllegalArgumentException.class, () -> ResidentAdoption.inspect(t, ID, "ag_Kirito")); }
}
