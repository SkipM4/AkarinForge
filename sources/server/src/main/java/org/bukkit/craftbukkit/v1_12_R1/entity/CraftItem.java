package org.bukkit.craftbukkit.v1_12_R1.entity;

import net.minecraft.entity.Entity;
import net.minecraft.entity.item.EntityItem;

import org.bukkit.craftbukkit.v1_12_R1.CraftServer;
import org.bukkit.craftbukkit.v1_12_R1.inventory.CraftItemStack;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;

public class CraftItem extends CraftEntity implements Item {
    private final EntityItem item;

    public CraftItem(CraftServer server, Entity entity, EntityItem item) {
        super(server, entity);
        this.item = item;
    }

    public CraftItem(CraftServer server, EntityItem entity) {
        this(server, entity, entity);
    }

    public ItemStack getItemStack() {
        return CraftItemStack.asCraftMirror(item.getItem());
    }

    public void setItemStack(ItemStack stack) {
        item.setItem(CraftItemStack.asNMSCopy(stack));
    }

    public int getPickupDelay() {
        return item.pickupDelay;
    }

    public void setPickupDelay(int delay) {
        item.pickupDelay = Math.min(delay, Short.MAX_VALUE);
    }

    @Override
    public String toString() {
        return "CraftItem";
    }

    public EntityType getType() {
        return EntityType.DROPPED_ITEM;
    }
}
