# AskBot

An AI chatbot for Minecraft **26.3** (Fabric) that lives in chat:

```
/askbot "how do I make a villager breeder?"
```

AskBot calls a configurable AI provider asynchronously, so the server tick loop is
never blocked. It ships with presets for OpenRouter, OpenAI, Anthropic, Gemini and
Ollama, and supports any OpenAI-compatible endpoint (LM Studio, vLLM, Groq,
Together, DeepSeek, Mistral, xAI, ...).

## Requirements

| Component | Version |
| --- | --- |
| Minecraft | 26.3 |
| Fabric Loader | 0.20.1 or newer |
| Fabric API | 0.166.2+26.3 (any 26.3 build) |
| Java | 25 |

AskBot is server-side friendly: clients do **not** need the mod installed. All
messages are sent as plain literal components, so nothing breaks for vanilla
clients.

## Install

### Server

1. Install Fabric Loader 26.3 and Fabric API.
2. Drop `askbot-1.0.0.jar` into the server's `mods/` folder.
3. Start the server once. AskBot creates `config/askbot.json`.
4. Put an API key in the config (or an environment variable) and run
   `/askbot reload`.

### Client (singleplayer or any server)

The same jar works as a client mod. Drop it into your client's `mods/`
folder (alongside Fabric API). It registers `/askbot` as a **local**
command: only you see the question and answer, and it works on servers
that do not have AskBot installed. Config is created at
`config/askbot.json` in your game directory on first launch.

## Configure

The generated config looks like this (shortened):

```json
{
  "defaultProvider": "openrouter",
  "systemPrompt": "You are AskBot, a helpful assistant inside a Minecraft Java Edition server...",
  "maxTokens": 512,
  "temperature": 0.7,
  "timeoutSeconds": 60,
  "maxQuestionLength": 1000,
  "maxResponseCharacters": 4000,
  "cooldownSeconds": 5,
  "cooldownBypassPermissionLevel": 2,
  "maxConcurrentRequests": 4,
  "permissionLevel": 0,
  "adminPermissionLevel": 3,
  "broadcast": false,
  "showThinking": true,
  "includePlayerContext": true,
  "includeCoordinates": false,
  "historyMessages": 6,
  "internetAccess": false,
  "prefix": "[AskBot] ",
  "providers": [
    {
      "id": "openrouter",
      "type": "openai",
      "enabled": true,
      "baseUrl": "https://openrouter.ai/api/v1",
      "apiKey": "${OPENROUTER_API_KEY}",
      "model": "openrouter/auto",
      "requiresApiKey": true,
      "maxTokensField": "max_tokens",
      "headers": {
        "X-Title": "AskBot"
      },
      "extraBody": {}
    }
  ]
}
```

### API keys

`apiKey` can be a literal key or an environment-variable reference:

```json
"apiKey": "${OPENROUTER_API_KEY}"
```

Using an environment variable keeps secrets out of the config file. On Linux,
start the server with:

```bash
export OPENROUTER_API_KEY="sk-or-v1-..."
java -Xmx4G -jar fabric-server-mc.26.3-loader.0.20.1-launcher.1.0.0.jar nogui
```

The default presets read these variables:

| Provider | Variable |
| --- | --- |
| OpenRouter | `OPENROUTER_API_KEY` |
| OpenAI | `OPENAI_API_KEY` |
| Anthropic | `ANTHROPIC_API_KEY` |
| Gemini | `GEMINI_API_KEY` |
| Custom | `CUSTOM_API_KEY` |

A missing variable becomes an empty string; AskBot reports which variable is
missing instead of sending a doomed request.

### Provider fields

| Field | Meaning |
| --- | --- |
| `id` | Unique name used by `/askbot provider <id>` |
| `type` | `openai`, `anthropic` or `gemini` |
| `baseUrl` | API root (no `/chat/completions` needed, but accepted) |
| `apiKey` | Literal key or `${ENV_VAR}` |
| `model` | Model name accepted by that provider |
| `enabled` | Disabled providers are ignored and cannot be selected |
| `requiresApiKey` | Set `false` for keyless local servers such as Ollama |
| `maxTokensField` | `max_tokens` (default), `max_completion_tokens`, or `none` |
| `maxTokens` | Per-provider override; omit to inherit the global value |
| `temperature` | Per-provider override; omit to inherit the global value |
| `headers` | Extra HTTP headers (e.g. OpenRouter's `HTTP-Referer`) |
| `extraBody` | Extra JSON merged into the top level of the request body |

`extraBody` is the escape hatch for provider-specific options:

```json
"extraBody": {
  "provider": { "order": ["anthropic", "openai"] },
  "reasoning": { "effort": "low" }
}
```

Nested objects are merged, other values replace the existing top-level key.

### Preset examples

**OpenRouter** (default):

```json
{
  "id": "openrouter",
  "type": "openai",
  "baseUrl": "https://openrouter.ai/api/v1",
  "apiKey": "${OPENROUTER_API_KEY}",
  "model": "openrouter/auto"
}
```

Use a specific model if you want predictable pricing, for example
`anthropic/claude-sonnet-4.5` or a `:free` variant. OpenRouter model names change
over time; check <https://openrouter.ai/models>.

**OpenAI**:

```json
{
  "id": "openai",
  "type": "openai",
  "baseUrl": "https://api.openai.com/v1",
  "apiKey": "${OPENAI_API_KEY}",
  "model": "gpt-4.1-mini"
}
```

**Anthropic**:

```json
{
  "id": "anthropic",
  "type": "anthropic",
  "baseUrl": "https://api.anthropic.com/v1",
  "apiKey": "${ANTHROPIC_API_KEY}",
  "model": "claude-sonnet-4-5"
}
```

**Google Gemini**:

```json
{
  "id": "gemini",
  "type": "gemini",
  "baseUrl": "https://generativelanguage.googleapis.com/v1beta",
  "apiKey": "${GEMINI_API_KEY}",
  "model": "gemini-2.5-flash"
}
```

**Ollama** (local, no key):

```json
{
  "id": "ollama",
  "type": "openai",
  "baseUrl": "http://localhost:11434/v1",
  "apiKey": "",
  "model": "llama3.2",
  "requiresApiKey": false
}
```

**LM Studio / vLLM / any OpenAI-compatible server**:

```json
{
  "id": "local",
  "type": "openai",
  "baseUrl": "http://localhost:1234/v1",
  "apiKey": "",
  "model": "your-model-name",
  "requiresApiKey": false
}
```

## Commands

| Command | Description |
| --- | --- |
| `/askbot <question>` | Ask the AI. Quotes around the question are optional and stripped. |
| `/askbot clear` | Clear your own conversation history. |
| `/askbot status` | Show provider, model, key presence and config values. |
| `/askbot provider <id>` | Switch the default provider and save it to the config. |
| `/askbot key <provider> <key>` | Set an API key for a provider in-game and save it (admin). |
| `/askbot model <model>` | Set the model for the default provider and save it (admin). |
| `/askbot model <provider> <model>` | Set a provider's model and save it (admin). |
| `/askbot models [provider]` | List available models from a provider. |
| `/askbot internet <on|off>` | Enable or disable AI web search (admin). |
| `/askbot mod <query>` | Search Modrinth for Fabric 26.3 mods and print download links. |
| `/askbot reload` | Re-read `config/askbot.json` without restarting. |
| `/askbot help` | Show command help. |

Examples:

```
/askbot "what should I build next to a village?"
/askbot how do I get ancient debris fast
/askbot "explain redstone comparators like I'm five"
```

## Permissions

Minecraft 26.x uses permission levels 0-4:

| Config value | Meaning |
| --- | --- |
| `0` | Everyone (default for `permissionLevel`) |
| `1` | Moderators |
| `2` | Gamemasters |
| `3` | Admins (default for `adminPermissionLevel`) |
| `4` | Server owners |

`cooldownBypassPermissionLevel` defaults to `2`, so staff are not rate-limited.

## Behaviour and safety

- Requests run on `java.net.http.HttpClient` and are delivered back with
  `server.execute(...)`, never on the netty/server thread.
- `maxConcurrentRequests` caps in-flight requests globally; excess questions get
  a friendly "busy" message.
- Per-player cooldowns (`cooldownSeconds`) and a question length limit reduce
  accidental spend.
- `historyMessages` keeps a small per-player conversation (default 6 messages,
  i.e. 3 turns). Set it to `0` for stateless questions. History is memory-only
  and cleared on `/askbot clear` and server shutdown.
- `broadcast: true` sends both the question and answer to every player. The
  default is `false` (only the asker sees the answer).
- `includePlayerContext` adds the player's name and dimension to the system
  prompt. Coordinates are only included when `includeCoordinates` is `true`.
- API keys are never logged. HTTP errors include the provider's message, with
  long bodies truncated.
- Questions and answers are sent to the configured third-party provider. Tell
  your players if that matters to you.

## Build from source

JDK 25 is required (Minecraft 26.3 targets Java 25).

```bash
./gradlew build
```

The jar is written to `build/libs/askbot-1.0.0.jar`.

For a development client/server run:

```bash
./gradlew runClient
./gradlew runServer
```

The first launch will create `run/config/askbot.json`.

## Troubleshooting

| Symptom | Fix |
| --- | --- |
| `Provider 'openrouter' has no API key` | Export the env var or put a literal key in the config, then `/askbot reload`. |
| `HTTP 401` | The key is invalid, expired, or lacks credit. |
| `HTTP 404` | Wrong `baseUrl` or model name. Model names change; check the provider's docs. |
| `HTTP 429` | Rate limit or no credit. Wait, switch models, or add billing. |
| `The request timed out` | Raise `timeoutSeconds`, or use a smaller/faster model. |
| `Unknown host` | Typo in `baseUrl`, or the server has no DNS/network access. |
| Local Ollama fails to connect | Make sure Ollama listens on an address the Minecraft server can reach, and set `requiresApiKey` to `false`. |

## License

MIT. See [LICENSE](LICENSE).

AskBot is not affiliated with Mojang, Microsoft, OpenRouter, OpenAI, Anthropic or
Google.

## Author

Made by **@rootrequired**.

- Telegram: https://t.me/rootrequired
- Instagram: https://instagram.com/rootrequired
