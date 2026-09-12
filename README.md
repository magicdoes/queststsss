# MagicQuests 1.3

MagicSMP quest plugin using Paper, Vault and optional PlaceholderAPI.

## This version changes

- Removes only the idle **"QUESTS • Choose a quest with /quests"** boss bar.
- Keeps the boss bar for a player's currently selected quest.
- Removes the cooldown between quests completely.
- After claiming a completed quest, another quest can be selected immediately.
- Quest rewards are paid through Vault.
- Clicking the completion message teleports the player to the configured Quest NPC and opens `/quests`.
- Quests reset as new cycles, so the same quest can be completed again after it appears in a later reset.

## Build on GitHub

1. Create a new GitHub repository.
2. Upload every file/folder from this project.
3. Open **Actions** → **Build MagicQuests** → **Run workflow**.
4. When it finishes, open the run and download the **MagicQuests** artifact.
5. Inside is `MagicQuests.jar`.

You can also build locally with:

```bash
mvn clean package
```

The JAR will be at `target/MagicQuests.jar`.

## Commands

- `/quests` - open quests menu
- `/quests setnpc` - save the Quest NPC location
- `/quests reset` - immediately rotate quests
- `/quests settime 15m` - change reset interval
- `/quests reload` - reload configuration/data

## Placeholders

- `%magicquests_time%`
- `%magicquests_next_reset%`
- `%magicquests_nextreset%`
- `%magicquests_interval%`
- `%magicquests_active%`
- `%magicquests_active_quests%`

- Active quest boss bars always start with **QUEST •** on the left (for example: `QUEST • Cow Hunter • 12/25`).
