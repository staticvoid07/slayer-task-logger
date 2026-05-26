# Slayer Task Logger

A RuneLite plugin that logs slayer task assignments and completions to a local text file, sends notifications via the Dink plugin, and optionally POSTs event data to a webhook URL.

## Features

- Logs new task assignments and completions to `~/.runelite/slayer-log.txt`
- Sends Dink notifications on task received and task completed
- POSTs structured JSON to a configurable webhook URL

## Log File

Events are appended to `~/.runelite/slayer-log.txt` with timestamps:

```
[2026-05-26 04:30:00] TASK RECEIVED: blue dragons x135
[2026-05-26 04:45:00] TASK COMPLETE: blue dragons x135 | XP: 5,400 | Tasks completed: 100 | Points: +20 | Total points: 500
```

## Dink Integration

To receive Dink notifications you must:

1. Install the **[Dink](https://github.com/pajlads/DinkPlugin)** plugin from the Plugin Hub
2. In Dink's settings, enable **"Allow External Plugin Requests"**
3. Enable **Dink on task received** and/or **Dink on task complete** in Slayer Logger's config

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
  "monster": "blue dragons",
  "amount": 135,
  "tasks": 100,
  "points": 20
}
```

`tasks` — total tasks completed streak  
`points` — points earned from this task (not the running total)  
`timestamp` — ISO 8601 UTC

## Config Options

| Option | Description | Default |
|---|---|---|
| Webhook URL | URL to POST slayer events to | *(empty)* |
| Dink on task received | Send a Dink notification on new task | Enabled |
| Dink on task complete | Send a Dink notification on task completion | Enabled |
