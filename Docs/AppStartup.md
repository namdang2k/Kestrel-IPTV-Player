# App startup

Build scene order: `Loading` (enabled, index 0), then `Main`.
`LoadingScene` loads Main asynchronously and updates its slider. Main owns
`AppFlow`; no global runtime initializer creates app UI in Loading.
Opening Main directly in the Editor also works.

## Canvas source

Select `AppFlow` in Main. `App Canvas Prefab` references
`Assets/Prefabs/AppCanvas.prefab` by default. Drag a compatible prefab into
this field to replace it.

To use Resources instead, move the prefab (keep its .meta/GUID) into
`Assets/Resources/AppCanvas.prefab`, clear `App Canvas Prefab`, and set
`App Canvas Resource Path` to `AppCanvas`. For subfolders, use e.g.
`UI/AppCanvas` without `Resources/` or `.prefab`. An assigned prefab takes
priority. A missing prefab or missing root Canvas logs an error and stops
initialization.

AppFlow instantiates one canvas under its scene object. Scene unload destroys
both. Profiles, EditProfile, and Status bind to existing prefab controls;
only profile list rows are created dynamically. Keep the named control paths
used by the screen constructors when editing the prefab. Button callbacks
are connected at runtime. Main already contains an EventSystem; one is
created if needed.

## Profile UI per platform

`AppFlow` drives the profile list and editor through `IProfileUi`. In the
Editor (and any non-Android build) `CanvasProfileUi` uses the prefab's
Profiles/EditProfile screens. On an Android device `AndroidProfileUi` uses
native views inside the StbHost AAR (`HomeScreen`, `ProfileEditorDialog`):
a home with Profile/Setting tabs, an Add/Edit popup and a delete
confirmation; the prefab's Profiles/EditProfile objects are deactivated.
Status (Connecting/Error) stays uGUI on both. An empty list shows the empty
state with Add profile instead of opening the form.

## Manual smoke check

- Play Loading: the loading UI appears, then Main has one AppCanvas.
- Play Main directly: the profile list appears (empty list shows the Add button).
- Add/edit/cancel/delete a profile and check the buttons and scrolling.
- Clear the direct reference after moving the prefab to Resources and repeat.
- Exit Play Mode before changing the scene or prefab reference.
