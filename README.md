# wAccountSwitcher

A standalone Fabric client mod for Minecraft Java 1.21.11. Open **Accounts** from the title screen.

## Login options

- **Microsoft sign-in:** uses Microsoft's device-code flow, then Xbox Live/XSTS and Minecraft Services to create a normal online session. A refresh action exchanges the saved Microsoft refresh token and renews the Minecraft session.
- **Offline account:** switches the local profile name and works only on servers configured for offline mode.
- **Session access token:** validates a Minecraft access token against Minecraft Services and adds the resulting profile. Minecraft access tokens do not provide a refresh token, so use Microsoft sign-in for refresh support.

Saved credentials are stored in `config/waccountswitcher-accounts.json` in the Minecraft game directory. This file contains access and Microsoft refresh tokens in readable form; protect it like a password file and remove it when you no longer want the saved sessions.

Install `wAccountSwitcher-1.0.0.jar` in the instance's `mods` folder. It needs Fabric Loader and does not require Fabric API. The distribution ZIP also contains the JAR under `mods/`.
