# The prompt

Paste this into Claude Code (or another coding assistant with shell access) on a computer on the
same Wi-Fi as the TV. It works on Android TV and Google TV sets. It builds on
[CleanYourTV](https://github.com/Gathin23/cleanyourtv): that repo's prompt and tools debloat the
TV, and this repo's launcher replaces CleanYourTV's. Clone both, side by side:

```sh
git clone https://github.com/Gathin23/cleanyourtv
git clone https://github.com/IyiolaJay/spotlight-tv
```

Before you start, on the TV: Settings → Device Preferences → About → press OK on "Build" 7 times,
then go back → Developer options → turn on USB debugging. Keep the TV on.

---

I want to debloat my Android TV and give it the Spotlight home screen. My TV model is:
<MODEL NAME HERE>. Both repos are cloned next to each other: `cleanyourtv/` and `spotlight-tv/`.
Work on the real device, step by step, and verify everything you do with screenshots pulled from
the TV. Ask me before anything that depends on my preferences.

Debloat with CleanYourTV
- Follow `cleanyourtv/PROMPT.md` for Connect, Back up first and Debloat, with its tools and its
  `examples/` lists. Its rules all apply: disable, never uninstall; never open factory packages;
  small batches with screenshots; reboot once and compare.
- Skip its Launcher section: Spotlight replaces CleanYourTV's launcher. Keep its checks before any
  launcher goes in: does the TV accept APKs, is its SDK at least `minSdk` in
  `spotlight-tv/launcher/app/build.gradle` (26 = Android 8.0), which other apps claim HOME.

Ask me, one question each
- What name should Spotlight greet me with ("Good evening, Ada"), shown faintly across the
  bottom right too? Or no name at all.
- Which apps go at the top of the list as favourites, and in what order? The rest follow by name.
- The background: the painted black sand beach it comes with, or a photo of mine (I'll give you
  the file; any size works, it is cropped to 16:9).
- Does the TV open Live TV by itself when it's switched on? If yes, power it off and on, check
  `dumpsys activity activities | grep mResumedActivity` straight after, and make sure that app is
  in `live_tv_packages` in `spotlight-tv/launcher/app/src/main/res/values/config.xml`.

Install
- Set up a Python venv in `spotlight-tv/` with `tools/requirements.txt`. Building needs a JDK
  (17 or newer) and the Android SDK.
- Run `TV_HOST=<ip> NAME=<name or none> FAVOURITES=<pkg,pkg> PHOTO=<file> BOOT_TO_HOME=on
  tools/install.sh`, leaving out what I didn't ask for. It builds Spotlight and grants what it
  needs over ADB. It makes Spotlight Home and checks the TV really switched.
- If Home didn't switch, a stock launcher outranks it. Follow CleanYourTV's prompt on disabling it
  safely (setupwraith on Google TV, CVTE boards), then run `tools/install.sh` again.
- The Wi-Fi pill shows the network's name only while Location is on. Ask me before turning it on
  (some TVs only allow it together with Google Location Accuracy). Without it the pill says Wi-Fi.

Verify
- Screenshot Home, then Up and Down through the list, Right to the actions, and Up to the pills.
  Open each pill's menu (All apps, Inputs, Wi-Fi, Settings), and Left/Right between them.
  `tools/tv.py --screenshot shots/01.png DPAD_DOWN` presses a key and captures the screen.
- Reboot once. Spotlight must come up as Home; with `BOOT_TO_HOME=on`, Live TV may show for a few
  seconds first, never longer.
- When done, close network ADB as CleanYourTV's prompt says (debugging off, authorisations revoked,
  `tools/adb-closed.py` reports CLOSED). Tell me how to change the name, favourites or background
  later (README, "Change settings later").

---

Expect four questions: your name, your favourite apps, the background and Live TV at power-on,
on top of CleanYourTV's debloat questions. Everything is reversible.
