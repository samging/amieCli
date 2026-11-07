# Devices

The **Dashboard** (route `home1/`) lists the devices you configured as cards. It is not reachable at the moment:
no sidebar icon leads to it and the route sends you on to the sandbox, so what follows describes the screens as
they are built, for when it is wired back in. Devices are stored
in `AMP/componentSettings.json` and, when the Amie backend is reachable, synced to the backend under the signed-in
user (a guest syncs under a fixed guest id).

## Adding a device

Press **+** in the window header. The **Add Device** screen asks for:

| Field | Meaning |
|---|---|
| Device name | How the card is titled |
| Port | The port the device listens on, typed (the serial ports the machine sees are listed under the card's configuration, **port**) |
| Endpoint | The device's address |

## A device card

Each card shows the name, endpoint and port, and four actions:

| Action | Screen |
|---|---|
| **Connect** | The connection panel: shows the endpoint and status, with a **Connect / Disconnect** switch |
| **Manage** | A terminal bound to the device (source `terminal/<deviceId>`, shown by the console's columns switch) where you can send commands and see its output |
| **Configure** | The device's configuration page |
| **Remove** | Deletes the device from the configuration and refreshes the dashboard |

Device terminals write to the same `AMP/logs.txt` as the sandbox console, tagged with their device id, so their
history comes back when you reopen them.
