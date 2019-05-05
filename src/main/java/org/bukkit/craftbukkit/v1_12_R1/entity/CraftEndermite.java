/*
 * Akarin Forge
 */
package org.bukkit.craftbukkit.v1_12_R1.entity;

import org.bukkit.craftbukkit.v1_12_R1.CraftServer;
import org.bukkit.craftbukkit.v1_12_R1.entity.CraftMonster;
import org.bukkit.entity.Endermite;
import org.bukkit.entity.EntityType;

public class CraftEndermite
extends CraftMonster
implements Endermite {
    public CraftEndermite(CraftServer server, acv entity) {
        super(server, entity);
    }

    @Override
    public String toString() {
        return "CraftEndermite";
    }

    @Override
    public EntityType getType() {
        return EntityType.ENDERMITE;
    }
}
