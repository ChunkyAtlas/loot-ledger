package com.example;

import com.lootledger.LootLedgerPlugin;
import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

@SuppressWarnings("ALL")
public class ExamplePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(LootLedgerPlugin.class);
		RuneLite.main(args);
	}
}