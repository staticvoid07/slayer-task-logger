package com.slayerlogger;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("slayerlogger")
public interface SlayerLoggerConfig extends Config
{
	@ConfigItem(
		keyName = "webhookUrl",
		name = "Webhook URL",
		description = "URL to POST slayer events to (leave empty to disable)"
	)
	default String webhookUrl()
	{
		return "";
	}

	@ConfigItem(
		keyName = "dinkOnCapePerkProc",
		name = "Dink on cape perk proc",
		description = "Send a Dink notification when the slayer cape perk triggers"
	)
	default boolean dinkOnCapePerkProc()
	{
		return true;
	}

	@ConfigItem(
		keyName = "dinkOnSuperiorSpawn",
		name = "Dink on superior spawn",
		description = "Send a Dink notification when a superior slayer creature appears"
	)
	default boolean dinkOnSuperiorSpawn()
	{
		return true;
	}

	@ConfigItem(
		keyName = "dinkOnTaskReceived",
		name = "Dink on task received",
		description = "Send a Dink notification when a new slayer task is assigned"
	)
	default boolean dinkOnTaskReceived()
	{
		return true;
	}

	@ConfigItem(
		keyName = "dinkOnTaskSkipped",
		name = "Dink on task skipped",
		description = "Send a Dink notification when a slayer task is skipped"
	)
	default boolean dinkOnTaskSkipped()
	{
		return true;
	}

	@ConfigItem(
		keyName = "dinkOnTaskComplete",
		name = "Dink on task complete",
		description = "Send a Dink notification when a slayer task is completed"
	)
	default boolean dinkOnTaskComplete()
	{
		return true;
	}
}
