package com.slayerlogger;

import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.events.ChatMessage;
import net.runelite.client.RuneLite;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.EventBus;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.PluginMessage;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.util.Text;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import javax.inject.Inject;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
@PluginDescriptor(
	name = "Slayer Task Logger",
	description = "Logs slayer task assignments and completions to a file and notifies Dink",
	tags = {"slayer", "log", "task", "tracker"}
)
public class SlayerLoggerPlugin extends Plugin
{
	// "Your new task is to kill 135 blue dragons."
	private static final Pattern TASK_RECEIVED_PATTERN = Pattern.compile(
		"Your new task is to kill (\\d+) (.+)\\."
	);

	// First message: "You have completed your task! You killed 267 Nechryael. You gained 56,280 xp"
	private static final Pattern TASK_COMPLETE_PART1_PATTERN = Pattern.compile(
		"You have completed your task! You killed (\\d+) (.+?)\\. You gained ([\\d,]+) (?:Slayer )?[Xx][Pp]\\.?"
	);

	// Second message: "You've completed 3,733 tasks and received 15 points, giving you a total of 2,983; return to a Slayer master."
	private static final Pattern TASK_COMPLETE_PART2_PATTERN = Pattern.compile(
		"You'?ve completed ([\\d,]+) tasks? and received ([\\d,]+) points?, giving you a total of ([\\d,]+);"
	);

	private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

	@Inject
	private Gson gson;

	@Inject
	private Client client;

	@Inject
	private EventBus eventBus;

	@Inject
	private OkHttpClient httpClient;

	@Inject
	private SlayerLoggerConfig config;

	private PrintWriter logWriter;

	// Holds data from part 1 of the split task completion message until part 2 arrives
	private int pendingKillCount;
	private String pendingMonster;
	private String pendingXp;
	private boolean awaitingCompletionPart2 = false;

	@Override
	protected void startUp() throws Exception
	{
		Path logPath = RuneLite.RUNELITE_DIR.toPath().resolve("slayer-log.txt");
		try
		{
			logWriter = new PrintWriter(new FileWriter(logPath.toFile(), true), true);
			log.debug("Slayer Logger started, logging to {}", logPath);
		}
		catch (IOException e)
		{
			log.error("Failed to open slayer log file at {}", logPath, e);
		}
	}

	@Override
	protected void shutDown() throws Exception
	{
		if (logWriter != null)
		{
			logWriter.close();
			logWriter = null;
		}
		awaitingCompletionPart2 = false;
		log.debug("Slayer Logger stopped!");
	}

	@Subscribe
	public void onChatMessage(ChatMessage chatMessage)
	{
		ChatMessageType type = chatMessage.getType();

		if (type != ChatMessageType.GAMEMESSAGE
			&& type != ChatMessageType.SPAM
			&& type != ChatMessageType.DIALOG
			&& type != ChatMessageType.MESBOX)
		{
			return;
		}

		// Text.removeTags strips all HTML-like tags including color codes (<col=...>) and line breaks.
		// Text.removeFormattingTags leaves color tags in place, breaking regex matches.
		String message = Text.removeTags(chatMessage.getMessage());

		Matcher taskReceived = TASK_RECEIVED_PATTERN.matcher(message);
		if (taskReceived.find())
		{
			int count = Integer.parseInt(taskReceived.group(1));
			String monster = taskReceived.group(2);
			handleTaskReceived(count, monster);
			return;
		}

		Matcher part1 = TASK_COMPLETE_PART1_PATTERN.matcher(message);
		if (part1.find())
		{
			pendingKillCount = Integer.parseInt(part1.group(1));
			pendingMonster = part1.group(2);
			pendingXp = part1.group(3);
			awaitingCompletionPart2 = true;
			return;
		}

		if (awaitingCompletionPart2)
		{
			Matcher part2 = TASK_COMPLETE_PART2_PATTERN.matcher(message);
			if (part2.find())
			{
				awaitingCompletionPart2 = false;
				int tasksCompleted = Integer.parseInt(part2.group(1).replace(",", ""));
				int pointsReceived = Integer.parseInt(part2.group(2).replace(",", ""));
				String totalPoints = part2.group(3);
				handleTaskComplete(pendingKillCount, pendingMonster, pendingXp, tasksCompleted, pointsReceived, totalPoints);
			}
		}
	}

	private void handleTaskReceived(int count, String monster)
	{
		String entry = String.format("[%s] TASK RECEIVED: %s x%d",
			LocalDateTime.now().format(FORMATTER), monster, count);
		writeLog(entry);

		if (config.dinkOnTaskReceived())
		{
			sendDinkNotification(
				"Slayer Task Received",
				String.format("New task: %s x%d", monster, count)
			);
		}

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("username", getPlayerName());
		payload.put("timestamp", Instant.now().toString());
		payload.put("message_type", "new task");
		payload.put("monster", monster);
		payload.put("amount", count);
		sendWebhook(payload);
	}

	private void handleTaskComplete(int killCount, String monster, String xp,
		int tasksCompleted, int pointsReceived, String totalPoints)
	{
		String entry = String.format(
			"[%s] TASK COMPLETE: %s x%d | XP: %s | Tasks completed: %d | Points: +%d | Total points: %s",
			LocalDateTime.now().format(FORMATTER), monster, killCount, xp,
			tasksCompleted, pointsReceived, totalPoints
		);
		writeLog(entry);

		if (config.dinkOnTaskComplete())
		{
			sendDinkNotification(
				"Slayer Task Complete",
				String.format("Completed: %s x%d | Tasks: %d | Points: +%d (%s total)",
					monster, killCount, tasksCompleted, pointsReceived, totalPoints)
			);
		}

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("username", getPlayerName());
		payload.put("timestamp", Instant.now().toString());
		payload.put("message_type", "task completed");
		payload.put("monster", monster);
		payload.put("amount", killCount);
		payload.put("xp", Integer.parseInt(xp.replace(",", "")));
		payload.put("tasks", tasksCompleted);
		payload.put("points", pointsReceived);
		payload.put("total_points", Integer.parseInt(totalPoints.replace(",", "")));
		sendWebhook(payload);
	}

	private String getPlayerName()
	{
		if (client.getLocalPlayer() != null && client.getLocalPlayer().getName() != null)
		{
			return client.getLocalPlayer().getName();
		}
		return "unknown";
	}

	private void sendWebhook(Map<String, Object> payload)
	{
		String url = config.webhookUrl();
		if (url == null || url.isBlank())
		{
			return;
		}

		String json = gson.toJson(payload);
		Request request = new Request.Builder()
			.url(url)
			.post(RequestBody.create(JSON, json))
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				log.warn("Webhook POST failed", e);
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				response.close();
				if (!response.isSuccessful())
				{
					log.warn("Webhook returned HTTP {}", response.code());
				}
			}
		});
	}

	private void writeLog(String entry)
	{
		log.debug(entry);
		if (logWriter != null)
		{
			logWriter.println(entry);
		}
	}

	private void sendDinkNotification(String title, String message)
	{
		HashMap<String, Object> data = new HashMap<>();
		data.put("text", message);
		data.put("sourcePlugin", getName());
		data.put("title", title);
		data.put("imageRequested", false);
		eventBus.post(new PluginMessage("dink", "notify", data));
	}

	@Provides
	SlayerLoggerConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(SlayerLoggerConfig.class);
	}
}
