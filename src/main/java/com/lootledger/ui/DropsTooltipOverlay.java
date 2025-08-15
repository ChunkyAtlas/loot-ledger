package com.lootledger.ui;

import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.FontMetrics;
import java.awt.Color;
import java.awt.Rectangle;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;

import com.lootledger.drops.DropItem;

import net.runelite.api.Client;
import net.runelite.api.Point;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Overlay that displays item name tooltips when hovering drop icons
 * injected into the music tab by {@link MusicWidgetController}.
 */
@Singleton
public class DropsTooltipOverlay extends Overlay
{
    private final Client client;
    private final MusicWidgetController widgetController;

    @Inject
    public DropsTooltipOverlay(
            Client client,
            MusicWidgetController widgetController
    )
    {
        this.client = client;
        this.widgetController = widgetController;
        setPosition(OverlayPosition.DYNAMIC);
        setLayer(OverlayLayer.ABOVE_WIDGETS);
    }

    @Override
    public Dimension render(Graphics2D graphics)
    {
        if (!widgetController.isOverrideActive())
        {
            return null;
        }

        final Point mouse = client.getMouseCanvasPosition();
        for (Map.Entry<Widget, DropItem> entry : widgetController.getIconItemMap().entrySet())
        {
            final Widget w = entry.getKey();
            if (w == null || w.isHidden())
            {
                continue;
            }
            final Rectangle bounds = w.getBounds();
            if (bounds != null && bounds.contains(mouse.getX(), mouse.getY()))
            {
                final DropItem drop = entry.getValue();
                drawTooltip(graphics, drop.getName(), drop.getOneOverRarity(), mouse);
                break;
            }
        }
        return null;
    }

    private void drawTooltip(Graphics2D g, String name, String rarity, Point mouse)
    {
        if (name == null) name = "";
        if (rarity == null) rarity = "";

        final FontMetrics fm = g.getFontMetrics();
        final int padding = 4;
        final int gap = 2;
        final int lineH  = fm.getHeight();

        final String rateText = "Rate: " + rarity;

        final int nameW = fm.stringWidth(name);
        final int rateW = fm.stringWidth(rateText);

        final int nameBoxW = nameW + padding * 2;
        final int rateBoxW = rateW + padding * 2;
        final int boxH     = lineH + padding * 2;
        final int totalH   = boxH * 2 + gap;
        final int clampW   = Math.max(nameBoxW, rateBoxW);

        int x = mouse.getX() + 10;
        int y = mouse.getY() - 10;

        Rectangle clip = g.getClipBounds();
        if (clip == null)
        {
            // Fallback to game canvas size if clip is null
            clip = new Rectangle(0, 0, client.getCanvasWidth(), client.getCanvasHeight());
        }

        x = Math.max(clip.x, Math.min(x, clip.x + clip.width  - clampW));
        y = Math.max(clip.y + totalH, Math.min(y, clip.y + clip.height));

        final int nameTop = y - totalH;
        drawBox(g, x, nameTop, nameBoxW, boxH);
        drawBox(g, x, y - boxH, rateBoxW, boxH);

        g.setColor(Color.WHITE);
        final int base = nameTop + padding + fm.getAscent();
        g.drawString(name, x + padding, base);

        final int rateBase = y - boxH + padding + fm.getAscent();
        final String prefix = "Rate: ";
        final int prefixW = fm.stringWidth(prefix);

        g.setColor(Color.WHITE);
        g.drawString(prefix, x + padding, rateBase);

        g.setColor(Color.ORANGE);
        g.drawString(rarity, x + padding + prefixW, rateBase);
    }

    private void drawBox(Graphics2D g, int x, int y, int w, int h)
    {
        g.setColor(new Color(0, 0, 0, 160));
        g.fillRect(x, y, w, h);
        g.setColor(new Color(50, 50, 50, 220));
        g.drawRect(x, y, w, h);
    }
}
