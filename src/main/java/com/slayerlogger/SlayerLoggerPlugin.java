package com.slayerlogger;

import com.google.gson.Gson;
import com.google.inject.Provides;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.GameState;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.client.RuneLite;
import net.runelite.client.callback.ClientThread;
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
import java.util.List;
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

	// First chat message on task completion: "You have completed your task! You killed 267 Nechryael. You gained 56,280 xp"
	private static final Pattern TASK_COMPLETE_PART1_PATTERN = Pattern.compile(
		"You have completed your task! You killed (\\d+) (.+?)\\. You gained ([\\d,]+) (?:Slayer )?[Xx][Pp]\\.?"
	);

	// Second chat message on task completion: "You've completed 3,733 tasks and received 15 points, giving you a total of 2,983; return to a Slayer master."
	private static final Pattern TASK_COMPLETE_PART2_PATTERN = Pattern.compile(
		"You'?ve completed ([\\d,]+) tasks? and received ([\\d,]+) points?, giving you a total of ([\\d,]+);"
	);

	// Task cancelled message (GAMEMESSAGE or DIALOG)
	private static final String TASK_CANCELLED_TEXT = "Your task has been cancelled.";

	// Slayer cape perk proc dialog
	private static final String CAPE_PERK_PROC_TEXT = "I might be able to do you a favour if you want";

	private static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
	private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

	@Inject
	private Gson gson;

	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private EventBus eventBus;

	@Inject
	private OkHttpClient httpClient;

	@Inject
	private SlayerLoggerConfig config;

	private PrintWriter logWriter;

	// Current task state — kept in sync via varbits so skip/cape proc always have correct data
	private String currentTaskName = "";
	private int currentOriginalAmount;
	private String currentArea = "";
	private int currentPoints;
	private int currentTasksCompleted;
	private int currentSlayerMaster;

	// Pending state for the two-part task completion chat message
	private boolean awaitingCompletionPart2 = false;
	private int pendingKillCount;
	private String pendingMonster;
	private String pendingXp;

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

		if (client.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invoke(this::syncCurrentTask);
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
		currentTaskName = "";
		log.debug("Slayer Logger stopped!");
	}

	// Silently syncs current task state from game memory — no notifications fired.
	// Covers: plugin enabled mid-session, login, world hop, stored task swaps.
	private void syncCurrentTask()
	{
		currentPoints = client.getVarbitValue(VarbitID.SLAYER_POINTS);
		currentTasksCompleted = client.getVarbitValue(VarbitID.SLAYER_TASKS_COMPLETED);
		currentSlayerMaster = client.getVarbitValue(VarbitID.SLAYER_MASTER);

		int amount = client.getVarpValue(VarPlayerID.SLAYER_COUNT);
		if (amount > 0)
		{
			String taskName = lookupTaskName();
			if (taskName != null)
			{
				currentTaskName = taskName.toLowerCase();
				currentOriginalAmount = client.getVarpValue(VarPlayerID.SLAYER_COUNT_ORIGINAL);
				currentArea = lookupAreaName();
			}
		}
		else
		{
			currentTaskName = "";
			currentArea = "";
		}
	}

	private String lookupAreaName()
	{
		int areaId = client.getVarpValue(VarPlayerID.SLAYER_AREA);
		if (areaId <= 0)
		{
			return "";
		}
		List<Integer> areaRows = client.getDBRowsByValue(
			DBTableID.SlayerArea.ID, DBTableID.SlayerArea.COL_AREA_ID, 0, areaId);
		if (areaRows.isEmpty())
		{
			return "";
		}
		return (String) client.getDBTableField(areaRows.get(0), DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER, 0)[0];
	}

	private String lookupTaskName()
	{
		int taskId = client.getVarpValue(VarPlayerID.SLAYER_TARGET);
		int taskDBRow;

		if (taskId == 98) // Boss task
		{
			List<Integer> bossRows = client.getDBRowsByValue(
				DBTableID.SlayerTaskSublist.ID,
				DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID,
				0,
				client.getVarbitValue(VarbitID.SLAYER_TARGET_BOSSID));
			if (bossRows.isEmpty())
			{
				return null;
			}
			taskDBRow = (Integer) client.getDBTableField(bossRows.get(0), DBTableID.SlayerTaskSublist.COL_TASK, 0)[0];
		}
		else
		{
			List<Integer> taskRows = client.getDBRowsByValue(
				DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, 0, taskId);
			if (taskRows.isEmpty())
			{
				return null;
			}
			taskDBRow = taskRows.get(0);
		}

		return (String) client.getDBTableField(taskDBRow, DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0)[0];
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		if (event.getGameState() == GameState.LOGGED_IN)
		{
			clientThread.invoke(this::syncCurrentTask);
		}
		else if (event.getGameState() == GameState.LOGIN_SCREEN
			|| event.getGameState() == GameState.HOPPING)
		{
			currentTaskName = "";
			awaitingCompletionPart2 = false;
		}
	}

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		int varpId = event.getVarpId();
		int varbitId = event.getVarbitId();

		if (varpId == VarPlayerID.SLAYER_TARGET
			|| varpId == VarPlayerID.SLAYER_COUNT
			|| varpId == VarPlayerID.SLAYER_COUNT_ORIGINAL
			|| varpId == VarPlayerID.SLAYER_AREA
			|| varbitId == VarbitID.SLAYER_TARGET_BOSSID)
		{
			clientThread.invokeLater(this::syncCurrentTask);
		}
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

		String message = Text.removeTags(chatMessage.getMessage());

		if (message.contains(CAPE_PERK_PROC_TEXT))
		{
			handleCapePerkProc();
			return;
		}

		if (message.contains(TASK_CANCELLED_TEXT))
		{
			handleTaskSkipped();
			return;
		}

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
		currentTaskName = monster;
		currentOriginalAmount = count;

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
		payload.put("total_points", currentPoints);
		sendWebhook(payload);
	}

	private void handleTaskSkipped()
	{
		String monster = currentTaskName.isEmpty() ? "unknown" : currentTaskName;
		int amount = currentOriginalAmount;
		String area = currentArea;
		int points = currentPoints;
		int tasksCompleted = currentTasksCompleted;
		int slayerMaster = currentSlayerMaster;
		currentTaskName = "";

		String entry = String.format("[%s] TASK SKIPPED: %s x%d | Area: %s | Points: %d | Tasks: %d | Master: %d",
			LocalDateTime.now().format(FORMATTER), monster, amount,
			area.isEmpty() ? "none" : area, points, tasksCompleted, slayerMaster);
		writeLog(entry);

		if (config.dinkOnTaskSkipped())
		{
			sendDinkNotification(
				"Slayer Task Skipped",
				String.format("Skipped: %s x%d", monster, amount)
			);
		}

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("username", getPlayerName());
		payload.put("timestamp", Instant.now().toString());
		payload.put("message_type", "task skipped");
		payload.put("monster", monster);
		payload.put("amount", amount);
		payload.put("area", area);
		payload.put("total_points", points);
		payload.put("tasks_completed", tasksCompleted);
		payload.put("slayer_master", slayerMaster);
		sendWebhook(payload);
	}

	private void handleTaskComplete(int killCount, String monster, String xp,
		int tasksCompleted, int pointsReceived, String totalPoints)
	{
		int originalAmount = currentOriginalAmount;
		currentTaskName = "";

		String entry = String.format(
			"[%s] TASK COMPLETE: %s | Assigned: %d | Killed: %d | XP: %s | Tasks completed: %d | Points: +%d | Total points: %s",
			LocalDateTime.now().format(FORMATTER), monster, originalAmount, killCount, xp,
			tasksCompleted, pointsReceived, totalPoints
		);
		writeLog(entry);

		if (config.dinkOnTaskComplete())
		{
			sendDinkNotification(
				"Slayer Task Complete",
				String.format("Completed: %s | Assigned: %d | Killed: %d | Tasks: %d | Points: +%d (%s total)",
					monster, originalAmount, killCount, tasksCompleted, pointsReceived, totalPoints)
			);
		}

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("username", getPlayerName());
		payload.put("timestamp", Instant.now().toString());
		payload.put("message_type", "task completed");
		payload.put("monster", monster);
		payload.put("amount", originalAmount);
		payload.put("kills", killCount);
		payload.put("xp", Integer.parseInt(xp.replace(",", "")));
		payload.put("tasks", tasksCompleted);
		payload.put("points", pointsReceived);
		payload.put("total_points", Integer.parseInt(totalPoints.replace(",", "")));
		sendWebhook(payload);
	}

	private void handleCapePerkProc()
	{
		String entry = String.format("[%s] CAPE PERK PROC: %s x%d | Area: %s | Points: %d | Tasks: %d | Master: %d",
			LocalDateTime.now().format(FORMATTER), currentTaskName.isEmpty() ? "unknown" : currentTaskName,
			currentOriginalAmount, currentArea.isEmpty() ? "none" : currentArea,
			currentPoints, currentTasksCompleted, currentSlayerMaster);
		writeLog(entry);

		if (config.dinkOnCapePerkProc())
		{
			sendDinkNotification("Slayer Cape Perk", "Cape perk proc!");
		}

		Map<String, Object> payload = new LinkedHashMap<>();
		payload.put("username", getPlayerName());
		payload.put("timestamp", Instant.now().toString());
		payload.put("message_type", "cape perk proc");
		payload.put("monster", currentTaskName.isEmpty() ? "unknown" : currentTaskName);
		payload.put("amount", currentOriginalAmount);
		payload.put("area", currentArea);
		payload.put("total_points", currentPoints);
		payload.put("tasks_completed", currentTasksCompleted);
		payload.put("slayer_master", currentSlayerMaster);
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
