# Changes

## Bug Fixes
* **Fixed Missing Right Click Options**: Added a proper context menu to the app, so you can now right-click to Cut, Copy, Paste, and Select All in text fields (like Notes).
* **Fixed Calculator**: The calculator would break if you typed incomplete formulas like "5+-3" or ended with an operator. It now correctly parses advanced inputs and negative numbers without failing.
* **Fixed Layout Stretching**: Resizing widgets used to stretch and blur the content. Now the layout scales crisply without any pixelation.
* **Fixed Quit Bug**: Fixed a typo (`app.isQuiting`) that prevented the app from closing down cleanly when you hit "Quit".
* **Fixed Slow Animations**: The Clock and Stopwatch were jumping chunks of time on a slow interval. They are now linked directly to the monitor's refresh rate for ultra-smooth 60+ FPS motion.

## Creative Ideas & Additions
* **Multi-Widgets**: You can now open unlimited numbers of any widget at the same time! Instead of toggling off your only note, opening Notes again spawns a brand-new instance for endless multitasking. The Widget Hub now shows a sleek badge counting how many of each widget you have open.
* **System Monitor Widget**: Added a brand new "SysMon" widget that shows your PC's real-time CPU usage, RAM capacity, and uptime in gorgeous animated rings.
* **Richer Glassmorphism**: Supercharged the visual design with a slowly panning dynamic background, a stronger backdrop blur effect, and an awesome moving neon conic-gradient border around the edge of all widgets.
* **Buttery Smooth Interactions**: Every button, input, and widget load now features highly responsive fluid CSS animations using cubic-bezier easing.
* **Automated Tests**: Added an e2e test script so developers can easily verify the app boots without catastrophic crashes in the future.
