package com.dwinovo.numen.api.brain;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

import java.util.UUID;

/** Read-only validation of an existing player save. Adoption never rewrites the player's save. */
final class ResidentAdoption {
    private ResidentAdoption() {}

    record Saved(String dimension, BlockPos position) {}

    static Saved inspect(CompoundTag tag, UUID id, String name) {
        if (!name.matches("[A-Za-z0-9_]{1,16}")) throw new IllegalArgumentException("角色名格式无效");
        if (!tag.hasUUID("UUID") || !id.equals(tag.getUUID("UUID")))
            throw new IllegalArgumentException("存档 UUID 与指定账号不一致");
        String savedName = tag.getCompound("bukkit").getString("lastKnownName");
        if (!savedName.equalsIgnoreCase(name))
            throw new IllegalArgumentException("存档角色名与指定账号不一致");
        if (tag.hasUUID("NumenOwner")) throw new IllegalArgumentException("已有同伴归属，不能重复接管");
        if (!tag.contains("Health", Tag.TAG_ANY_NUMERIC) || !(tag.getFloat("Health") > 0))
            throw new IllegalArgumentException("角色已死亡，请先按正常流程复活");
        var pos = tag.getList("Pos", Tag.TAG_DOUBLE);
        if (pos.size() != 3 || !Double.isFinite(pos.getDouble(0)) || !Double.isFinite(pos.getDouble(1))
                || !Double.isFinite(pos.getDouble(2)) || Math.abs(pos.getDouble(0)) > 29999984
                || Math.abs(pos.getDouble(2)) > 29999984 || Math.abs(pos.getDouble(1)) > 2048)
            throw new IllegalArgumentException("存档位置无效");
        String dimension = tag.getString("Dimension");
        if (dimension.isBlank()) throw new IllegalArgumentException("存档没有维度");
        return new Saved(dimension, BlockPos.containing(pos.getDouble(0), pos.getDouble(1), pos.getDouble(2)));
    }
}
