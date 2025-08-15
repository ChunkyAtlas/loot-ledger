package com.lootledger.drops;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Setter
@Getter
@NoArgsConstructor
public class NpcDropData
{
    private int npcId;
    private String name;
    private int level;
    private List<DropTableSection> dropTableSections;

    public NpcDropData(int npcId, String name, int level, List<DropTableSection> dropTableSections)
    {
        this.npcId = npcId;
        this.name = name;
        this.level = level;
        this.dropTableSections = dropTableSections;
    }
}
