# Odin IMGUI compatibility repair (Unity 6000.6.3f1)

The bundled Odin reflection bridge references members that moved in Unity:

- UIElementsUtility.s_BeginContainerCallback -> UIElementsIMGUIUtility
- UIElementsUtility.s_EndContainerCallback -> UIElementsIMGUIUtility
- UIElementsUtility.GetCurrentIMGUIContainer -> IMGUIContainer

Tools/Fix-OdinUnity66.ps1 validates member signatures against the installed
UnityEngine.UIElementsModule.dll and updates these three member references
(nine IL instructions) in Sirenix.Reflection.Editor.dll. It preserves
subscriptions and method behavior; it does not suppress exceptions or disable
Odin initialization. Running it again is a no-op. The original DLL is backed
up to Temp/OdinUnity66Backup/Sirenix.Reflection.Editor.dll.original. The tracked
original can also be recovered from git.

After Unity imports the changed DLL, clear old Console entries and verify a
domain reload. This patch addresses the reported IMGUI initialization failures,
not every compatibility issue in this older Odin build. Existing Editor logs
also contain a separate InstanceIDToObject failure in Odin UndoTracker.

The supported long-term repair is updating the full Odin installation. Odin
4.0.2 release notes explicitly document the Unity 6.6 IMGUI member moves:
https://odininspector.com/patch-notes/4-0-2
Replace this locally patched DLL with the vendor DLL when upgrading.
