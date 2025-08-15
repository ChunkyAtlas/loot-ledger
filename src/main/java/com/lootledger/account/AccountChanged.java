package com.lootledger.account;

import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

@Getter
@ToString
@EqualsAndHashCode
public final class AccountChanged
{
	public static final long NO_ACCOUNT = -1L;

	private final long hash;
	private final String playerName;

	public AccountChanged(long hash, String playerName)
	{
		this.hash = hash;
		this.playerName = playerName;
	}

	public boolean isLoggedIn()
	{
		return hash != NO_ACCOUNT;
	}
}
