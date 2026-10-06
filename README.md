# SiteRuler

A tradesman's measuring app for Android that uses the phone's camera and motion sensors (ARCore). It's built for surveyors, engineers, architects, landscapers and builders doing as-builts and site measurements. It collects room outlines, ceiling heights and point-to-point distances, sketches the rooms into a floor plan you arrange on screen, and exports the plan as DXF (AutoCAD, BricsCAD, DraftSight, SketchUp and similar) and PDF.

## What it does

Tap the trade name at the top (**SiteRuler · Surveying ▾**) to pick the kind of work; the app asks the first time. Each trade shows the tools it uses:

| Trade | Tools |
|---|---|
| Architecture | Distance, Height, Room, Points, Plan |
| Surveying | Distance, Height, Points, Topo, Volume |
| Engineering | Distance, Height, Points, Topo, Volume (EG/FG codes) |
| Landscaping | Distance, Area (Room without a ceiling step), Points, Topo, Plan, Volume |

- **Distance**: aim the crosshair at a start point, tap **+**, aim at the end point, tap **+**. Gives the straight-line 3D distance (door widths, window sizes, wall runs).
- **Height**: tap **+** on the floor, then at the top point. Only the vertical part counts, so the top point doesn't need to be directly above.
- **Room**: tap **+** on the floor at each corner, going around the room, then **Close room**. Wall lengths are measured flat on the floor, and the app shows each wall, the perimeter and the floor area. It then asks for the ceiling: aim where a wall meets the ceiling and tap **+**, or **Skip height**. Name the room and it is saved to the job.
- **Points**: shoot survey points. Each one gets a number and a description (BM, IP, TC...). Mark a shot as a **control point** and enter its known North, East and (optionally) elevation, from a benchmark, property pins or a plat. With two or more control points in the same setup, every point in that setup is rotated and shifted onto that grid (best fit, no scale), and the fit shows the worst control residual so you can judge it. With one control point the shift is applied but the bearing is arbitrary. With none, points get assumed coordinates (first point N 5000, E 5000, Z 100). Each AR session is a new setup; to carry coordinates into a new setup, shoot two earlier points again and use "Use the coordinates of an earlier point". **Job → Points** lists everything, and **Copy for SiteMath** puts the points on the clipboard as P,N,E,Z,D. Export adds `_points.csv` (PNEZD, no header) and `_points.dxf` (POINTs with number and description labels). Coordinates are in feet or meters, following the units setting. Points carry a source field so a Bluetooth RTK receiver can be added later.
- **Topo** (surveying, engineering, landscaping): fast elevation shots for volumes. Aim at the ground and tap **+** at every high spot, low spot and change in slope, and around the edge of the area; each shot saves at once with the next point number and the current code. Tap **Code** to switch codes (GND, TOP, TOE, EG, FG...). Once the setup has a point, the live readout shows the elevation under the crosshair. **Undo** removes the last shot.
- **Volume**: earthwork volumes from the shots. The ground is a TIN (Delaunay triangles through the chosen points) and is measured against one of:
  - **Grade**: a flat design elevation you type in. Gives cut, fill and net.
  - **Stockpile**: the plane through the surface's own edge points, for a pile (volume above) or a pit (volume below).
  - **Surface**: a second set of points by code, for example existing ground (EG) against finished grade (FG).
  The plan view shades cut red and fill blue. Volumes are in cubic yards (and cubic feet) or cubic meters. **Share** sends a text report, `_surface.dxf` (3DFACE triangles on layer `TIN` at true elevation) and the PNEZD point file.
- **Ruler**: a true-scale ruler along any edge of the screen (top, right, bottom or left), in inches (1/16") or millimeters, for small objects. Drag the two red lines to the ends of the object to read its length. Left and right run down the long side for the longest ruler. Calibrate it once with a credit card, because phones don't always report their exact screen density.
- **Plan**: the floor plan sketch. Every measured room appears with its wall lengths, name, area and ceiling height. Drag a room to move it (corners snap onto nearby corners of other rooms), rotate the selected room 90° either way, drag empty space to pan, and pinch to zoom. The arrangement is saved and is what gets exported.
- **Job**: the list of everything measured. Tap an item to delete it, rename the job (use the address), or start a new one. The job is saved on the phone and survives closing the app.
- **Export** (from the main screen or the Plan): shares these files by email, Drive, etc.:
  - `.pdf`: the plan sketch on a letter-size page with the job name, date and a scale bar.
  - `.dxf`: the same arrangement, with each room's walls on layer `WALLS`, wall lengths on `DIMS`, room name, area and ceiling height on `ROOMS`, and the other measurements as a list on `NOTES`. Imperial drawings use inches as the drawing unit, metric uses meters.
  - `.csv` with every wall, perimeter, height, area and distance in meters and feet-inches.
  - `.json` with the raw data.
- **Units** toggles between feet-inches (to the nearest 1/2") and metric.

The crosshair is green on a solid surface (a detected floor or wall, or a depth hit), yellow when the hit is rough (a loose feature point, or more than 3 m away), and red when there's nothing to measure against. Faint white outlines show the surfaces ARCore has found.

## Accuracy, and how to get the most out of it

ARCore is typically within about 1 to 2 cm over a few meters when tracking is good, and error grows with distance and with how far you walk. For as-built work that means:

- **Rooms are measured independently** and then assembled on the Plan screen. Stitching a whole floor into one AR session would accumulate drift, so this is deliberate. Corner snapping joins rooms at a shared corner; leave a gap for wall thickness where you know it, or adjust in CAD.
- **Stay within about 3 m** of what you're measuring and walk slowly. Long walls are more accurate measured in one go from the middle of the room than by walking along them.
- **Scan first.** Sweep the phone across the floor and walls for a few seconds before tapping, until the white outlines appear.
- **Corners**: aim at the floor right at the corner rather than at the wall, because the floor plane is found more reliably.
- **Light and texture matter.** Plain white walls and dark rooms track poorly. Turn lights on.
- **Phones with depth sensors** (ToF, or ARCore Depth API support) hit walls and ceilings without needing a detected plane; the app turns depth on automatically when the phone supports it.
- **Check one dimension per room with a tape or laser** until you trust it on your phone. For anything structural or permit-critical, a laser measure is still the reference.

Not handled yet: doors and windows as openings, wall thickness, fine rotation (only 90° steps), and non-vertical walls.

## Building

Requirements: Android Studio (Ladybug or newer) or JDK 17 plus the Android SDK (platform 34), and an [ARCore-supported phone](https://developers.google.com/ar/devices).

1. Open this folder in Android Studio, let Gradle sync, and press Run with the phone plugged in (USB debugging on).
2. Or from a terminal: `./gradlew assembleDebug`, then install `app/build/outputs/apk/debug/app-debug.apk`.
3. Every push to `main` builds the APK on GitHub and publishes it as the **Latest build** release. On the phone, download https://github.com/Superduty335/Phone-Ruler/releases/latest/download/SiteRuler.apk, open it, and allow installing from your browser when asked.

The phone will prompt to install or update "Google Play Services for AR" the first time if it isn't already present.

## Code layout

- `MainActivity.kt`: ARCore session, camera rendering loop, buttons, job dialogs.
- `PlanActivity.kt`, `ui/PlanView.kt`, `ui/PlanRenderer.kt`: the floor plan sketch (drag, snap, rotate, zoom), also used to draw the PDF.
- `MeasureController.kt`: the measuring logic for the three modes, run on the render thread.
- `model/`: `Job`, `RoomRecord`, `LineRecord`, unit formatting, JSON save/load.
- `export/Exporter.kt`: DXF (AutoCAD R12), CSV and JSON writers. `export/PlanPdf.kt`: PDF sketch. `export/Share.kt`: share sheet.
- `render/`: OpenGL camera background and line drawing.
- `ui/OverlayView.kt`: crosshair and measurement labels.
