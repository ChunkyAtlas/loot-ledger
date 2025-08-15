package com.lootledger.ui;

import com.lootledger.drops.DropItem;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.MenuEntry;
import net.runelite.api.widgets.Widget;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public final class WidgetUtils
{
    private WidgetUtils() {}

    /**
     * Creates a "Show Drops" menu entry modeled after the given anchor entry.
     * Ensures a non-negative insert index and mirrors target/identifier/params
     * so the line sits near the source action.
     */
    public static MenuEntry createShowDropsEntry(
            Client client,
            int insertIndex,
            MenuEntry anchorEntry
    )
    {
        Objects.requireNonNull(client, "client");
        Objects.requireNonNull(anchorEntry, "anchorEntry");

        final int idx = Math.max(0, insertIndex);
        return client.getMenu()
                .createMenuEntry(idx)
                .setOption("Show Drops")
                .setTarget(anchorEntry.getTarget())
                .setIdentifier(anchorEntry.getIdentifier())
                .setParam0(anchorEntry.getParam0())
                .setParam1(anchorEntry.getParam1())
                .setType(MenuAction.RUNELITE);
    }

    /**
     * Hides all static and dynamic children of the given widget, if any exist,
     * and requests a revalidate on the parent.
     */
    public static void hideAllChildrenSafely(Widget widget)
    {
        if (widget == null)
        {
            return;
        }

        Widget[] staticKids = widget.getChildren();
        if (staticKids != null)
        {
            for (Widget child : staticKids)
            {
                if (child != null) child.setHidden(true);
            }
        }

        Widget[] dynamicKids = widget.getDynamicChildren();
        if (dynamicKids != null)
        {
            for (Widget child : dynamicKids)
            {
                if (child != null) child.setHidden(true);
            }
        }

        widget.revalidate();
    }

    /**
     * Deduplicates a list of DropItems by item ID and optionally sorts them by rarity.
     * <p>
     * When sorting by rarity, the resulting list is ordered from most common to rarest.
     * Unknown/unsupported rarities are treated as rarest.
     */
    public static List<DropItem> dedupeAndSort(List<DropItem> drops, boolean sortByRarity)
    {
        return drops.stream()
                .filter(d -> d != null && d.getItemId() > 0)
                .collect(Collectors.toMap(
                        DropItem::getItemId,
                        d -> d,
                        (first, second) -> first,
                        LinkedHashMap::new
                ))
                .values().stream()
                .sorted(sortByRarity
                        ? Comparator.comparingDouble(DropItem::getRarityValue)
                        .thenComparingInt(DropItem::getItemId)
                        : Comparator.comparingInt(DropItem::getItemId))
                .collect(Collectors.toList());
    }
}
