# Slayer Task Logger

A RuneLite plugin that logs slayer task assignments, completions, skips, and cape perk procs to a local text file, sends notifications via the Dink plugin, and optionally POSTs event data to a webhook URL.

## Features

- Logs task assignments, completions, skips, and cape perk procs to `~/.runelite/slayer-log.txt`
- Sends Dink notifications for each event type
- POSTs structured JSON to a configurable webhook URL
- Tracks current task state via game varbits so skips and cape procs always carry accurate data, even when using the slayer cape's task storage feature

## Log File

Events are appended to `~/.runelite/slayer-log.txt` with timestamps:

```
[2026-05-26 04:30:00] TASK RECEIVED: blue dragons x135
[2026-05-26 04:45:00] TASK COMPLETE: blue dragons x135 | XP: 5,400 | Tasks completed: 100 | Points: +20 | Total points: 500
[2026-05-26 04:50:00] TASK SKIPPED: blue dragons x135 | Area: none | Points: 500 | Tasks: 100 | Master: 9
[2026-05-26 04:55:00] CAPE PERK PROC: abyssal demons x185 | Area: none | Points: 500 | Tasks: 100 | Master: 9
```

## Dink Integration

To receive Dink notifications you must:

1. Install the **[Dink](https://github.com/pajlads/DinkPlugin)** plugin from the Plugin Hub
2. In Dink's settings, enable **"Allow External Plugin Requests"**
3. Enable the relevant Dink toggles in Slayer Logger's config

Without those steps the rest of the plugin (file logging and webhook) will still work fine.

## Webhook

Set a URL in the **Webhook URL** config field to receive POST requests on each slayer event. Leave it blank to disable.

**Headers**
```
Content-Type: application/json
```

### New Task

Fired when a slayer master assigns a task.

```json
{
  "username": "Zezima",
  "timestamp": "2026-05-26T04:30:00Z",
  "message_type": "new task",
  "monster": "blue dragons",
  "amount": 135
}
```

### Task Completed

Fired when a task is finished.

```json
{
  "username": "Zezima",
  "timestamp": "2026-05-26T04:45:00Z",
  "message_type": "task completed",
  "monster": "nechryael",
  "amount": 267,
  "kills": 267,
  "xp": 56280,
  "tasks": 3735,
  "points": 15,
  "total_points": 3013
}
```

`amount` — originally assigned task size  
`kills` — actual kills (differs from `amount` when using the extend task option)  
`tasks` — total tasks completed streak  
`points` — points earned from this task (not the running total)  
`timestamp` — ISO 8601 UTC

### Task Skipped

Fired when a task is cancelled. All fields reflect the state at the time the task was active, correctly handling slayer cape task storage swaps.

```json
{
  "username": "Zezima",
  "timestamp": "2026-05-26T04:50:00Z",
  "message_type": "task skipped",
  "monster": "araxytes",
  "amount": 120,
  "area": "",
  "points": 3013,
  "tasks_completed": 3735,
  "slayer_master": 9
}
```

`area` — Konar task area name, empty string if none  
`slayer_master` — numeric varbit ID of the assigning master (`7` = Krystilia)

### Cape Perk Proc

Fired when the slayer master offers a favour (slayer cape perk triggered). Includes full current task state at time of proc.

```json
{
  "username": "Zezima",
  "timestamp": "2026-05-26T04:55:00Z",
  "message_type": "cape perk proc",
  "monster": "abyssal demons",
  "amount": 185,
  "area": "",
  "points": 3013,
  "tasks_completed": 3735,
  "slayer_master": 9
}
```

## Config Options

| Option | Description | Default |
|---|---|---|
| Webhook URL | URL to POST slayer events to | *(empty)* |
| Dink on cape perk proc | Send a Dink notification on cape perk proc | Enabled |
| Dink on task received | Send a Dink notification on new task | Enabled |
| Dink on task skipped | Send a Dink notification on task skip | Enabled |
| Dink on task complete | Send a Dink notification on task completion | Enabled |
