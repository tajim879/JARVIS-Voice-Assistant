# JARVIS plugin examples

A plugin is one JSON file describing one HTTPS call. Install it in the app:
**Settings > Pro Capabilities > Plugin System > Add plugin (paste JSON)**, then restart the Jarvis session.
Jarvis can then use it by voice in any language as a tool named `plugin_<name>`.

| Field | Meaning |
|---|---|
| `name` | 2-32 characters: a-z, 0-9, `_` |
| `description` | tells Jarvis when to use it |
| `method` | GET, POST, PUT or PATCH |
| `url` | must start with `https://`; use `{{param}}` placeholders |
| `headers` | optional, up to 8 |
| `body` | optional request body, `{{param}}` placeholders |
| `params` | each parameter: `type` (string, number, integer, boolean) and `description` |
| `required` | list of required parameter names |
| `response_path` | optional dotted path into a JSON answer, e.g. `data.0.title` |
| `confirm` | `true` = always ask the user first (non-GET always asks) |
