package com.lootledger;

import net.runelite.client.config.*;

@ConfigGroup("lootledger")
public interface LootLedgerConfig extends Config
{
    enum Scope { PER_ACCOUNT, PER_NPC }
    enum ObtainedView { ALL, HIDE_OBTAINED, ONLY_OBTAINED }

    @ConfigItem(
            keyName = "trackObtained",
            name = "Track obtained items",
            description = "Enable tracking of items you loot. Uses the scope below.",
            position = 0
    )
    default boolean trackObtained() { return false; }

    @ConfigItem(
            keyName = "obtainedScope",
            name = "Tracking scope",
            description = "Store obtained items per account or per NPC.",
            position = 1
    )
    default Scope obtainedScope() { return Scope.PER_ACCOUNT; }

    @ConfigItem(
            keyName = "obtainedView",
            name = "Obtained visibility",
            description = "How to display obtained drops in the Music tab.",
            position = 2
    )
    default ObtainedView obtainedView() { return ObtainedView.ALL; }

    // Existing options kept (gem/RDT + sort)
    @ConfigItem(
            keyName = "showRareDropTable",
            name = "Show Rare Drop Table",
            description = "Include RDT sections in drop lists.",
            position = 10
    )
    default boolean showRareDropTable() { return true; }

    @ConfigItem(
            keyName = "showGemDropTable",
            name = "Show Gem Drop Table",
            description = "Include gem table sections in drop lists.",
            position = 11
    )
    default boolean showGemDropTable() { return true; }

    @ConfigItem(
            keyName = "sortDropsByRarity",
            name = "Sort by rarity",
            description = "Sort drops from common to rare (unknowns last).",
            position = 12
    )
    default boolean sortDropsByRarity() { return true; }
}
