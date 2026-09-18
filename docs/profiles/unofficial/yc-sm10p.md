# Portworld YC-SM10P profile notes

> [!IMPORTANT]
> This is an import-only community profile based on the hardware report in GitHub [#138](https://github.com/panel-assistant/android/issues/138). It is not bundled, automatically selected, maintainer-validated or fleet-qualified.

The reported YC-SM10P is a 10.1-inch Portworld panel on a Rockchip RK3566 running Android 11 userdebug. Its profile fingerprint requires both model `sm10p` and device `rk3566_r`, but it remains a manual choice for an owner who can confirm that hardware.

| | |
|---|---|
| SoC | Rockchip RK3566, four Arm Cortex-A55 cores |
| Firmware | `C06-V21.3-YC-10.1MIPI-TV101WXM-NM1-SW0.6-20231013-270` (Android 11, userdebug, `rk3566_r`) |
| Display | 1280×800 at logical 160 dpi, landscape |
| Root | app-accessible Android-style `su`; the bundled helper cannot persist on this firmware |
| LED / buttons | no user LED and no physical buttons |
| Screen off | `su-blpower`; asleep, `bl_power` is 4 and brightness is 0; on wake they return to 0 and 120 |
| Light / proximity | absent: I2C bus 4 address `0x24` returns ENXIO even though the device tree names EM3071x nodes |

## What the profile declares

The profile selects the hardware-verified `su-blpower` screen-off route and declares no LED. It does not declare ambient-light or proximity support: the kernel driver binds to the device-tree entries but cannot reach a part, Android creates neither sensor device, and subscriptions fail. Leaving those fields absent prevents entities for unavailable hardware.

The firmware names an `adc-keys` device, but the enclosure has no buttons, so `evdev_buttons` remains empty. The device tree also lists a GC2145 camera node, but Android exposes no camera; the profile makes no camera declaration.

## Limitations

- The profile is based on one public hardware report. It does not establish support for other YC-SM10P firmware or adjacent RK3566 board revisions.
- The root helper cannot persist across a reboot on the reported firmware: dm-verity is enforcing and `/system` is full. The reported direct `su` route remains sufficient for the declared screen-off driver, but that is not a promise about other firmware.
- No ambient-light or proximity sensor is available on the reported panel. The device tree names `4-0024:ls_em3071x` and `4-0024-1:ps_em3071x`, but the part does not answer on the bus; `i2cget -f -y 4 0x24 0x00` returns ENXIO.
- `/sys/class/leds` contains only `mmc2::`, which is card activity rather than a user-facing panel LED.
- No physical buttons are present, no CHT8305 temperature or humidity sensor is available, and Android exposes no camera.

Follow the [unofficial catalog procedure](README.md) to import [`community-yc-sm10p.yaml`](community-yc-sm10p.yaml), validate it and activate it while somebody can see and touch the panel. After restart, confirm that screen off and wake behave as expected, then exercise **Roll back** before relying on the profile unattended.
