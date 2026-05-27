# Slayer Task Logger

A RuneLite plugin that logs slayer task assignments, completions, skips, superior spawns, and cape perk procs to a local text file, sends notifications via the Dink plugin, and optionally POSTs event data to a webhook URL.

## Features

- Logs task assignments, completions, skips, superior spawns, and cape perk procs to `~/.runelite/slayer-log.txt`
- Sends Dink notifications for each event type
- POSTs structured JSON to a configurable webhook URL
- Tracks current task state via game varbits so events always carry accurate data, even when using the slayer cape's task storage feature
- Supports Konar's task assignment message format

## Log File

Events are appended to `~/.runelite/slayer-log.txt` with timestamps:

```
[2026-05-26 04:30:00] TASK RECEIVED: blue dragons x135 | Tasks: 100 | Points: 500
[2026-05-26 04:30:00] TASK RECEIVED: fire giants x128 | Area: Giants' Den | Tasks: 101 | Points: 500
[2026-05-26 04:45:00] TASK COMPLETE: nechryael | Assigned: 267 | Killed: 267 | XP: 56,280 | Tasks completed: 3735 | Points: +15 | Total points: 3013
[2026-05-26 04:50:00] TASK SKIPPED: araxytes x120 | Area: none | Points: 470 | Tasks: 3735 | Master: 9
[2026-05-26 04:52:00] SUPERIOR SPAWN: nechryael | Area: none | Points: 500 | Tasks: 3735 | Master: 9
[2026-05-26 04:55:00] CAPE PERK PROC: abyssal demons x185 | Area: none | Points: 500 | Tasks: 3735 | Master: 9
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
  "monster": "fire giants",
  "amount": 128,
  "area": "Giants' Den",
  "tasks_completed": 100,
  "total_points": 500
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
  "area": "",
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

### Task Skipped

Fired when a task is cancelled. All fields reflect the state at time of cancellation, correctly handling slayer cape task storage swaps. `total_points` is the balance **after** the skip cost is deducted.

```json
{
  "username": "Zezima",
  "timestamp": "2026-05-26T04:50:00Z",
  "message_type": "task skipped",
  "monster": "araxytes",
  "amount": 120,
  "area": "",
  "total_points": 470,
  "tasks_completed": 3735,
  "slayer_master": 9
}
```

### Superior Spawn

Fired when a superior slayer creature appears.

```json
{
  "username": "Zezima",
  "timestamp": "2026-05-26T04:52:00Z",
  "message_type": "superior spawn",
  "monster": "nechryael",
  "amount": 267,
  "area": "",
  "total_points": 500,
  "tasks_completed": 3735,
  "slayer_master": 9
}
```

### Cape Perk Proc

Fired when the slayer master offers a favour. The monster reflects the last known task — persists after a skip so a proc immediately following a skip correctly identifies the skipped task.

```json
{
  "username": "Zezima",
  "timestamp": "2026-05-26T04:55:00Z",
  "message_type": "cape perk proc",
  "monster": "abyssal demons",
  "amount": 185,
  "area": "",
  "total_points": 3013,
  "tasks_completed": 3735,
  "slayer_master": 9
}
```

## Config Options

| Option | Description | Default |
|---|---|---|
| Webhook URL | URL to POST slayer events to | *(empty)* |
| Dink on cape perk proc | Send a Dink notification on cape perk proc | Enabled |
| Dink on superior spawn | Send a Dink notification on superior spawn | Enabled |
| Dink on task received | Send a Dink notification on new task | Enabled |
| Dink on task skipped | Send a Dink notification on task skip | Enabled |
| Dink on task complete | Send a Dink notification on task completion | Enabled |
